# AI/insight/nodes.py
"""
Insight Agent 의 4단계 노드 — 인식/계획/행동/반영.

모델은 인사이트 후보를 쓰고, 코드가 거른다 (CRITERIA §4).
출처, 기관 간 비교 여부, 수신 기관 후보는 모델에게 묻지 않고 코드가 계산한다 (§3, §7).
"""

from . import config
from .llm import LlmError, ask_json
from .prompts import build_messages
from .schemas import Insight, RecipientCandidate, Support


def perceive(state: dict) -> dict:
    """인식: claim_id 로 claim 을 바로 찾을 수 있게 색인을 만든다."""
    state["claim_index"] = {c["claim_id"]: c for c in state["claims"]}
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
        return {**state, "raw_insights": [], "llm_called": True, "llm_error": str(exc)}

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
    if config.COUNT_DISTINCT_DATES:
        return len({index[i]["entry_date"] for i in ids})
    return len(ids)


def _clean_supports(raw, index: dict) -> list[Support]:
    """근거 번호가 없거나 결과가 비어 있는 대처는 그 대처만 버린다 (§4)."""
    kept = []
    for s in raw if isinstance(raw, list) else []:
        if not isinstance(s, dict):
            continue
        ids = _ids(s.get("claim_ids"))
        action = (s.get("action") or "").strip()
        result = (s.get("result") or "").strip()
        if not action:
            continue
        if config.REQUIRE_SUPPORT_EVIDENCE and not _all_known(ids, index):
            continue
        if config.REQUIRE_SUPPORT_RESULT and not result:
            continue
        kept.append(Support(action=action, result=result, claim_ids=ids))
    return kept


def _recipients(source_inst_ids: set[int], cross: bool, consented: list[dict]) -> list[RecipientCandidate]:
    """
    수신 기관 후보 (§7). 후보는 동의한 기관 밖으로 나갈 수 없다.
    추천 = 근거가 나오지 않은 기관. 기관 간 비교 인사이트는 전부 추천.
    """
    out = []
    for inst in consented:
        is_new = inst["institution_id"] not in source_inst_ids
        recommended = (config.RECOMMEND_NEW_INSTITUTIONS and is_new) or (config.RECOMMEND_ALL_ON_CROSS and cross)
        out.append(RecipientCandidate(
            institution_id=inst["institution_id"],
            institution_type=inst["institution_type"],
            recommended=recommended,
        ))
    return out


def reflect(state: dict) -> dict:
    """
    반영: 최종 결정은 코드가 한다.

    순서 (§4)
      목록 밖 claim_id → 인사이트 버림
      근거 claim 이 최소 수 미만 → 인사이트 버림
      상황·반응·본문 중 비어 있는 것 → 인사이트 버림
      대처는 하나씩 검사해서 문제 있는 것만 버림
    그다음 출처·기관·수신 후보를 claim 에서 계산한다.
    """
    if state.get("llm_error"):
        return {**state, "insights": []}  # API 에서는 503 으로 바뀐다 (graph.py)

    index = state["claim_index"]
    insights = []

    for raw in state["raw_insights"]:
        if not isinstance(raw, dict):
            continue

        ids = _ids(raw.get("claim_ids"))
        if config.REQUIRE_VALID_CLAIM_IDS and not _all_known(ids, index):
            continue
        ids = [i for i in ids if i in index]
        if _evidence_count(ids, index) < config.MIN_EVIDENCE_CLAIMS:
            continue

        texts = {k: (raw.get(k) or "").strip() for k in ("situation", "behavior", "content")}
        if not all(texts.values()):
            continue

        claims = [index[i] for i in ids]
        inst_ids = {c["institution_id"] for c in claims}
        cross = len(inst_ids) >= 2

        insights.append(Insight(
            **texts,
            supports=_clean_supports(raw.get("supports"), index),
            claim_ids=ids,
            source_entry_ids=sorted({e["journal_entry_id"] for c in claims for e in c["evidence"]}),
            institution_types=sorted({c["institution_type"] for c in claims}),
            cross_institution=cross,
            recipients=_recipients(inst_ids, cross, state.get("consented_institutions", [])),
        ))

    if config.MAX_INSIGHTS is not None:
        insights = insights[: config.MAX_INSIGHTS]
    return {**state, "insights": insights}