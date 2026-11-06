# AI/insight/nodes.py
"""
Insight Agent 의 4단계 노드 — 인식/계획/행동/반영.

모델은 인사이트 후보를 쓰고, 코드가 거른다 (CRITERIA §4).
출처와 기관 간 비교 여부는 모델에게 묻지 않고 코드가 계산한다 (§3).
수신 기관은 정하지 않는다. BE 가 Gate 2 시점의 동의로 정한다 (§7).
"""

from . import config
from .quality import NOT_IN_RECORD, weak_reasons
from .llm import LlmError, ask_json
from .prompts import build_messages, build_refine_messages
from .schemas import Insight, Support


def perceive(state: dict) -> dict:
    """
    인식: 동의하지 않은 기관의 claim 을 먼저 뺀 뒤, claim_id 색인을 만든다.

    받는 쪽만 막으면, 철회된 기관 기록이 인사이트 재료로 들어가
    그 내용이 다른 기관으로 흘러간다 (#109). BE 도 거르지만 여기서 한 번 더 거른다.
    동의 목록이 비어 있으면 아무 claim 도 쓰지 않는다 — 모르면 막는 쪽이 안전하다.
    """
    claims = state["claims"]
    dropped = []

    if config.FILTER_UNCONSENTED_SOURCES:
        allowed = {i["institution_id"] for i in state.get("consented_institutions", [])}
        dropped = [c["claim_id"] for c in claims if c["institution_id"] not in allowed]
        claims = [c for c in claims if c["institution_id"] in allowed]

    if dropped:
        print(f"[insight] 동의 밖 기관 claim {len(dropped)}건 제외")

    state["claims"] = claims  # 프롬프트도 이 목록만 본다
    state["dropped_claim_ids"] = dropped
    state["claim_index"] = {c["claim_id"]: c for c in claims}
    return state


def plan(state: dict) -> dict:
    """계획: claim 이 최소 근거 수보다 적으면 모델을 부르지 않는다. 성립할 인사이트가 없다."""
    state["skip_llm"] = len(state["claim_index"]) < config.MIN_EVIDENCE_CLAIMS
    return state


def act(state: dict) -> dict:
    """행동: Luna 호출. 여기서만 LLM 을 쓴다."""
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


# ── 반영에서 쓰는 검사들 ────────────────────────────────────────


def _ids(value) -> list[str]:
    """모델이 준 claim_ids 를 문자열 목록으로. 순서는 지키고 중복은 뺀다."""
    if not isinstance(value, list):
        return []
    out = []
    for v in value:
        if isinstance(v, str) and v not in out:
            out.append(v)
    return out


def _all_known(ids: list[str], index: dict) -> bool:
    """가리킨 번호가 하나 이상이고, 전부 입력에 있는가. 매칭의 '명부 밖 ID 는 버린다' 와 같다."""
    return bool(ids) and all(i in index for i in ids)


def _evidence_count(ids: list[str], index: dict) -> int:
    if config.COUNT_DISTINCT_RECORDS:
        return len({(index[i]["entry_date"], index[i]["institution_id"]) for i in ids})
    return len(ids)

def _source_of(claim: dict) -> str:
    # source 가 없으면 AI 다 — 옛 요청과 같은 뜻으로 읽는다 (schemas.InsightClaim 기본값)
    return claim.get("source") or "AI"


def _teacher_ids(ids: list[str], index: dict) -> list[str]:
    """교사가 고치거나 쓴 claim 번호. 순서는 처음 나온 순, 중복 없이."""
    return list(dict.fromkeys(i for i in ids if i in index and _source_of(index[i]) != "AI"))


def _institutions_grounded(texts: list[str], cited_types: set[str], known_types: set[str]) -> bool:
    """
    글에 나온 기관 종류가 전부 근거 claim 의 기관 종류 안에 있는가.
    "센터에서는…" 이라고 썼는데 근거가 학교 claim 뿐이면 False.
    """
    if not config.REQUIRE_INSTITUTION_GROUNDING:
        return True
    joined = " ".join(texts)
    mentioned = {t for t in known_types if t in joined}
    return mentioned <= cited_types


def _clean_supports(raw, index: dict, known_types: set[str]) -> list[Support]:
    """
    문제 있는 대처는 그 대처만 버린다 (§4).

    근거 번호가 하나도 없는 대처는 플래그와 무관하게 항상 건너뛴다.
    Support.claim_ids 는 최소 1개라, 빈 채로 만들면 ValidationError 가 난다.
    REQUIRE_SUPPORT_EVIDENCE 는 "가리킨 번호가 전부 입력에 있는가" 만 정한다.
    """
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
            continue  # 교사 문장만으로 된 대처는 쓰지 않는다. 받는 기관이 따라 하는 건 대처다

        support_types = {index[i]["institution_type"] for i in ids if i in index}
        if not _institutions_grounded([action, result], support_types, known_types):
            continue

        kept.append(Support(action=action, result=result, claim_ids=ids))
    return kept


def _build(raw, index: dict, known_types: set[str]) -> Insight | None:
    """
    모델이 쓴 인사이트 하나를 성립 조건(§4)으로 검사하고, 통과하면 출처를 채워 만든다.

    순서
      목록 밖 claim_id → 버림
      근거 claim 이 최소 수 미만 → 버림
      근거에 AI 원문 claim 이 없음 → 버림
      상황·반응·본문 중 비어 있는 것 → 버림
      본문에 나온 기관 종류가 근거 기관에 없음 → 버림
      대처는 하나씩 검사해서 문제 있는 것만 버림
    reflect 와 refine 이 같은 검사를 쓴다. 고쳐 쓴 인사이트도 처음부터 다시 통과해야 한다.
    """
    if not isinstance(raw, dict):
        return None

    ids = _ids(raw.get("claim_ids"))
    if config.REQUIRE_VALID_CLAIM_IDS and not _all_known(ids, index):
        return None
    ids = [i for i in ids if i in index]
    if _evidence_count(ids, index) < config.MIN_EVIDENCE_CLAIMS:
        return None
    if config.REQUIRE_SOURCE_CLAIM and not any(_source_of(index[i]) == "AI" for i in ids):
        return None  # 교사 문장만으로 된 인사이트는 만들지 않는다

    texts = {k: (raw.get(k) or "").strip() for k in ("situation", "behavior", "content")}
    if not all(texts.values()):
        return None

    claims = [index[i] for i in ids]
    cited_types = {c["institution_type"] for c in claims}
    if not _institutions_grounded(list(texts.values()), cited_types, known_types):
        return None

    supports = _clean_supports(raw.get("supports"), index, known_types)
    support_ids = [i for s in supports for i in s.claim_ids]
    # 출처는 대처 근거까지 — 철회 시 그 기관을 찾고, Gate 2 가 "근거를 낸 기관" 으로 보여준다
    all_claims = [index[i] for i in dict.fromkeys(ids + support_ids) if i in index]
    main_inst_ids = {c["institution_id"] for c in claims}

    return Insight(
        **texts,
        supports=supports,
        claim_ids=ids,
        source_entry_ids=sorted({
            e["journal_entry_id"]
            for c in all_claims for e in c["evidence"]
            if e["journal_entry_id"] is not None  # 교사 작성 문장은 일지가 없다
        }),
        source_institution_ids=sorted({c["institution_id"] for c in all_claims}),
        institution_types=sorted({c["institution_type"] for c in all_claims}),
        cross_institution=len(main_inst_ids) >= 2,  # 기관 간 차이 여부는 본문 근거로만
        teacher_claim_ids=_teacher_ids(ids + support_ids, index),
    )


def _known_types(state: dict) -> set[str]:
    return set(config.INSTITUTION_TYPES) | {c["institution_type"] for c in state["claims"]}


def reflect(state: dict) -> dict:
    """
    반영: 최종 결정은 코드가 한다. 성립 조건은 _build, 약한 인사이트는 quality.py.

    약한 인사이트 (INSIGHT_WEAK_MODE)
      off    검사만 하고 그대로 낸다. weak_found 에 남긴다 — 루프 전 비율을 재야 한다
      drop   걸린 것을 버린다
      refine 한기관_대처없음 은 버리고(고칠 정보가 없다), 기록에없는말 만 refine 노드로 넘긴다
    """
    if state.get("llm_error"):
        return {**state, "insights": []}  # API 에서는 503 으로 바뀐다 (graph.py)

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
        elif mode == "refine" and kinds == {NOT_IN_RECORD}:
            # 자리를 지켜 둔다. refine 이 고친 것으로 바꾸거나 뺀다
            to_refine.append({"pos": len(insights), "raw": raw, "reasons": reasons})
            insights.append(ins)
        else:
            weak_dropped.append(record)

    if config.MAX_INSIGHTS is not None and not to_refine:
        insights = insights[: config.MAX_INSIGHTS]
    return {**state, "insights": insights, "weak_found": weak_found,
            "weak_dropped": weak_dropped, "to_refine": to_refine, "weak_refined": []}


def refine(state: dict) -> dict:
    """
    수정 루프 (딥다이브 1, C): 기록에없는말 인사이트를 한 번만 고쳐 쓰게 한다.

    피드백은 코드가 찾은 문제 문장 그대로다. 고친 것은 _build(성립 조건)와 quality(약한 검사)를
    처음부터 다시 통과해야 하고, 처음 인사이트가 가리킨 claim 밖의 근거는 쓸 수 없다.
    통과 못 하거나 Luna 가 실패하면 그 인사이트만 버린다. 요청 전체를 실패시키지 않는다.
    """
    todo = state.get("to_refine") or []
    if not todo:
        return state

    index = state["claim_index"]
    known_types = _known_types(state)
    insights = list(state["insights"])
    replace, drop, log = {}, set(), []

    for item in todo:
        raw = item["raw"]
        allowed = set(_ids(raw.get("claim_ids"))) | {
            i for s in (raw.get("supports") or []) if isinstance(s, dict) for i in _ids(s.get("claim_ids"))
        }
        new_raw, error, why = None, None, ""
        try:
            result = ask_json(build_refine_messages(state, raw, item["reasons"], sorted(allowed)))
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
