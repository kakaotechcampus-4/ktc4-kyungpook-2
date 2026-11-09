import re
import time

from common import progress

from . import config
from .quality import NOT_IN_RECORD, SINGLE_NO_SUPPORT, weak_reasons
from .tools import TOOL_SPECS, find_claims
from .llm import LlmError, ask_json, ask_with_tools
from .prompts import build_lookup_messages, build_messages, build_refine_messages
from .schemas import Insight, Support


def perceive(state: dict) -> dict:
    claims = state["claims"]
    dropped = []

    if config.FILTER_UNCONSENTED_SOURCES:
        allowed = {i["institution_id"] for i in state.get("consented_institutions", [])}
        dropped = [c["claim_id"] for c in claims if c["institution_id"] not in allowed]
        claims = [c for c in claims if c["institution_id"] in allowed]

    if dropped:
        print(f"[insight] 동의 밖 기관 claim {len(dropped)}건 제외")

    state.setdefault("started_at", time.monotonic())
    state["claims"] = claims
    state["dropped_claim_ids"] = dropped
    state["claim_index"] = {c["claim_id"]: c for c in claims}

    history = state.get("history_claims") or []
    if config.FILTER_UNCONSENTED_SOURCES:
        history = [c for c in history if c["institution_id"] in allowed]
    state["history_index"] = {c["claim_id"]: c for c in history if c["claim_id"] not in state["claim_index"]}
    return state


def plan(state: dict) -> dict:
    state["skip_llm"] = len(state["claim_index"]) < config.MIN_EVIDENCE_CLAIMS
    return state


def act(state: dict) -> dict:
    if state["skip_llm"]:
        return {**state, "raw_insights": [], "llm_called": False, "llm_error": None}
    try:
        result = ask_json(build_messages(state))
    except LlmError as exc:
        return {**state, "raw_insights": [], "llm_called": True, "llm_error": str(exc),
                "llm_failure": exc.kind}

    raw = result.data.get("insights", [])
    return {**state, "raw_insights": raw if isinstance(raw, list) else [],
            "llm_called": True, "llm_error": None}


def _ids(value) -> list[str]:
    if not isinstance(value, list):
        return []
    out = []
    for v in value:
        if isinstance(v, str) and v not in out:
            out.append(v)
    return out


def _all_known(ids: list[str], index: dict) -> bool:
    return bool(ids) and all(i in index for i in ids)


def _evidence_count(ids: list[str], index: dict) -> int:
    if config.COUNT_DISTINCT_RECORDS:
        return len({(index[i]["entry_date"], index[i]["institution_id"]) for i in ids})
    return len(ids)

def _source_of(claim: dict) -> str:
    return claim.get("source") or "AI"


def _teacher_ids(ids: list[str], index: dict) -> list[str]:
    return list(dict.fromkeys(i for i in ids if i in index and _source_of(index[i]) != "AI"))


def _institutions_grounded(texts: list[str], cited_types: set[str], known_types: set[str]) -> bool:
    if not config.REQUIRE_INSTITUTION_GROUNDING:
        return True
    joined = " ".join(texts)
    mentioned = {t for t in known_types if t in joined}
    return mentioned <= cited_types


def _clean_supports(raw, index: dict, known_types: set[str]) -> list[Support]:
    kept = []
    for s in raw if isinstance(raw, list) else []:
        if not isinstance(s, dict):
            continue
        ids = _ids(s.get("claim_ids"))
        action = (s.get("action") or "").strip()
        result = (s.get("result") or "").strip()

        if not action or not ids:
            continue
        if config.REQUIRE_SUPPORT_EVIDENCE and not all(i in index for i in ids):
            continue
        if config.REQUIRE_SUPPORT_RESULT and not result:
            continue
        if config.REQUIRE_SOURCE_CLAIM and not any(_source_of(index[i]) == "AI" for i in ids if i in index):
            continue

        support_types = {index[i]["institution_type"] for i in ids if i in index}
        if not _institutions_grounded([action, result], support_types, known_types):
            continue

        kept.append(Support(action=action, result=result, claim_ids=ids))
    return kept


_TEACHER_TONE = re.compile(r"[가-힣]다(?:[.!?]|$)|[가-힣](?:함|됨|음)(?:[.!?]|$)")


def _tone_violations(insights: list) -> int:
    return sum(1 for i in insights if _TEACHER_TONE.search(i.content))


def _build(raw, index: dict, known_types: set[str]) -> Insight | None:
    if not isinstance(raw, dict):
        return None

    ids = _ids(raw.get("claim_ids"))
    if config.REQUIRE_VALID_CLAIM_IDS and not _all_known(ids, index):
        return None
    ids = [i for i in ids if i in index]
    if _evidence_count(ids, index) < config.MIN_EVIDENCE_CLAIMS:
        return None
    if config.REQUIRE_SOURCE_CLAIM and not any(_source_of(index[i]) == "AI" for i in ids):
        return None

    texts = {k: (raw.get(k) or "").strip() for k in ("situation", "behavior", "content")}
    if not all(texts.values()):
        return None

    claims = [index[i] for i in ids]
    cited_types = {c["institution_type"] for c in claims}
    if not _institutions_grounded(list(texts.values()), cited_types, known_types):
        return None

    supports = _clean_supports(raw.get("supports"), index, known_types)
    support_ids = [i for s in supports for i in s.claim_ids]
    all_claims = [index[i] for i in dict.fromkeys(ids + support_ids) if i in index]
    main_inst_ids = {c["institution_id"] for c in claims}

    return Insight(
        **texts,
        supports=supports,
        claim_ids=ids,
        source_entry_ids=sorted({
            e["journal_entry_id"]
            for c in all_claims for e in c["evidence"]
            if e["journal_entry_id"] is not None
        }),
        source_institution_ids=sorted({c["institution_id"] for c in all_claims}),
        institution_types=sorted({c["institution_type"] for c in all_claims}),
        cross_institution=len(main_inst_ids) >= 2,
        teacher_claim_ids=_teacher_ids(ids + support_ids, index),
    )


def _known_types(state: dict) -> set[str]:
    return set(config.INSTITUTION_TYPES) | {c["institution_type"] for c in state["claims"]}


def reflect(state: dict) -> dict:
    if state.get("llm_error"):
        return {**state, "insights": []}

    index = state["claim_index"]
    known_types = _known_types(state)
    mode = config.INSIGHT_WEAK_MODE
    insights, weak_found, weak_dropped, to_refine = [], [], [], []

    for raw in state["raw_insights"]:
        ins = _build(raw, index, known_types)
        if ins is None:
            continue
        reasons = weak_reasons(ins)
        if not reasons:
            insights.append(ins)
            continue

        record = {"content": ins.content, "claim_ids": ins.claim_ids, "reasons": reasons}
        weak_found.append(record)
        kinds = {r["kind"] for r in reasons}
        if mode == "off":
            insights.append(ins)
        elif mode == "refine" and len(to_refine) >= config.INSIGHT_REFINE_MAX:
            weak_dropped.append(record)
        elif mode == "refine" and kinds == {NOT_IN_RECORD}:
            to_refine.append({"pos": len(insights), "raw": raw, "reasons": reasons})
            insights.append(ins)
        elif (mode == "refine" and config.INSIGHT_TOOLS and SINGLE_NO_SUPPORT in kinds
              and kinds <= {SINGLE_NO_SUPPORT, NOT_IN_RECORD} and state.get("history_index")):
            to_refine.append({"pos": len(insights), "raw": raw, "reasons": reasons, "action": "lookup"})
            insights.append(ins)
        else:
            weak_dropped.append(record)

    if config.MAX_INSIGHTS is not None and not to_refine:
        insights = insights[: config.MAX_INSIGHTS]
    return {**state, "insights": insights, "weak_found": weak_found, "weak_dropped": weak_dropped,
            "to_refine": to_refine, "weak_refined": [], "tone_violations": _tone_violations(insights)}


def refine(state: dict) -> dict:
    todo = state.get("to_refine") or []
    if not todo:
        return state

    index = state["claim_index"]
    known_types = _known_types(state)
    insights = list(state["insights"])
    replace, drop, log = {}, set(), []
    deadline = state.get("started_at", time.monotonic()) + config.INSIGHT_DEADLINE

    for item in todo:
        if deadline - time.monotonic() < 10:
            drop.add(item["pos"])
            log.append({"before": insights[item["pos"]].content, "after": None, "outcome": "dropped",
                        "why": "시간 상한", "action": item.get("action", "refine")})
            continue
        if item.get("action") == "lookup":
            ins, entry = _lookup(state, item, known_types, deadline)
            if ins is not None:
                replace[item["pos"]] = ins
            else:
                drop.add(item["pos"])
            log.append({"before": insights[item["pos"]].content, **entry})
            continue

        raw = item["raw"]
        allowed = set(_ids(raw.get("claim_ids"))) | {
            i for s in (raw.get("supports") or []) if isinstance(s, dict) for i in _ids(s.get("claim_ids"))
        }
        new_raw, error, why = None, None, ""
        try:
            result = ask_json(build_refine_messages(state, raw, item["reasons"], sorted(allowed)),
                              deadline=deadline)
            got = result.data.get("insights")
            new_raw = got[0] if isinstance(got, list) and got else None
        except LlmError as exc:
            error = exc.kind

        ins = None
        if isinstance(new_raw, dict):
            used = set(_ids(new_raw.get("claim_ids"))) | {
                i for s in (new_raw.get("supports") or []) if isinstance(s, dict) for i in _ids(s.get("claim_ids"))
            }
            if not used <= allowed:
                why = "새 근거"
            else:
                ins = _build(new_raw, index, known_types)
                why = "성립 조건" if ins is None else ""
        else:
            why = error or "빈 답"

        left = weak_reasons(ins) if ins is not None else []
        if ins is not None and not left:
            replace[item["pos"]] = ins
            outcome = "rescued"
        else:
            drop.add(item["pos"])
            outcome = "dropped"
            why = why or "여전히 약함"
        log.append({"before": insights[item["pos"]].content, "after": ins.content if ins else None,
                    "outcome": outcome, "why": why})

    insights = [replace.get(p, ins) for p, ins in enumerate(insights) if p not in drop]
    if config.MAX_INSIGHTS is not None:
        insights = insights[: config.MAX_INSIGHTS]
    return {**state, "insights": insights, "weak_refined": log, "refine_calls": len(todo)}


def _lookup(state: dict, item: dict, known_types: set[str],
            deadline: float | None = None) -> tuple[Insight | None, dict]:
    raw = item["raw"]
    history = state.get("history_index") or {}
    returned: dict[str, dict] = {}
    scores: dict[str, int] = {}

    def run_tool(name: str, args: dict) -> list[dict]:
        if name != "find_claims":
            return []
        progress.mark("find_claims")
        found = find_claims(list(history.values()), args.get("situation", ""),
                            weeks_back=args.get("weeks_back"), before=state.get("period_from"),
                            limit=config.INSIGHT_TOOL_LIMIT, scores=scores)
        for c in found:
            returned[c["claim_id"]] = history[c["claim_id"]]
        return found

    original = set(_ids(raw.get("claim_ids"))) | {
        i for s in (raw.get("supports") or []) if isinstance(s, dict) for i in _ids(s.get("claim_ids"))
    }
    try:
        instructions, user = build_lookup_messages(state, raw, sorted(original), item.get("reasons"))
        result = ask_with_tools(instructions, user, TOOL_SPECS, run_tool,
                                max_calls=config.INSIGHT_TOOL_MAX_CALLS, deadline=deadline)
    except LlmError as exc:
        return None, {"after": None, "outcome": "dropped", "why": exc.kind, "action": "lookup",
                      "tool_calls": 0, "looked_up": [], "scores": scores}
    finally:
        progress.mark("refine")

    calls = result.usage.get("tool_calls", 0)
    got = result.data.get("insights")
    new_raw = got[0] if isinstance(got, list) and got else None
    if not isinstance(new_raw, dict):
        return None, {"after": None, "outcome": "dropped", "why": "같은 상황 없음", "action": "lookup",
                      "tool_calls": calls, "looked_up": sorted(returned), "scores": scores}

    used = set(_ids(new_raw.get("claim_ids"))) | {
        i for s in (new_raw.get("supports") or []) if isinstance(s, dict) for i in _ids(s.get("claim_ids"))
    }
    if not used <= original | set(returned):
        return None, {"after": None, "outcome": "dropped", "why": "조회 밖 근거", "action": "lookup",
                      "tool_calls": calls, "looked_up": sorted(returned), "scores": scores}

    index = {**state["claim_index"], **returned}
    ins = _build(new_raw, index, known_types | {c["institution_type"] for c in returned.values()})
    if ins is None or weak_reasons(ins):
        why = "성립 조건" if ins is None else "여전히 약함"
        return None, {"after": ins.content if ins else None, "outcome": "dropped", "why": why,
                      "action": "lookup", "tool_calls": calls, "looked_up": sorted(returned), "scores": scores}

    original_insts = {index[i]["institution_id"] for i in original if i in index}
    looked = sorted(used & set(returned))
    if not ins.cross_institution or not any(returned[i]["institution_id"] not in original_insts for i in looked):
        return None, {"after": ins.content, "outcome": "dropped", "why": "기관 간 아님", "action": "lookup",
                      "tool_calls": calls, "looked_up": sorted(returned), "scores": scores}

    ins.looked_up_claim_ids = looked
    return ins, {"after": ins.content, "outcome": "rescued", "why": "", "action": "lookup",
                 "tool_calls": calls, "looked_up": ins.looked_up_claim_ids, "scores": scores}
