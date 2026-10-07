# AI/insight/nodes.py
"""
Insight Agent 의 4단계 노드 — 인식/계획/행동/반영.

모델은 인사이트 후보를 쓰고, 코드가 거른다 (CRITERIA §4).
출처와 기관 간 비교 여부는 모델에게 묻지 않고 코드가 계산한다 (§3).
누가 볼 수 있는지(허락)는 정하지 않는다. 쓸모 태그만 거르고, 후보와 추천은 BE 가 정한다 (§7).
"""

from . import config
from .llm import LlmError, ask_json
from .prompts import build_messages
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

        support_types = {index[i]["institution_type"] for i in ids if i in index}
        if not _institutions_grounded([action, result], support_types, known_types):
            continue

        kept.append(Support(action=action, result=result, claim_ids=ids))
    return kept


def _relevance(raw: dict) -> tuple[list[str], str]:
    """
    쓸모 태그 (§4·§7). 학교·센터·학원 밖의 값은 그 값만 뺀다.

    허락이 아니라 추천 표시에만 쓰므로, 틀린 값이 있어도 인사이트는 버리지 않는다.
    근거를 낸 기관이 아니어도 남긴다 — 상황으로 판단한 결과이기 때문이다.
    남은 태그가 없으면 이유도 비운다.
    """
    value = raw.get("relevant_institution_types")
    types = []
    for t in value if isinstance(value, list) else []:
        if isinstance(t, str) and t in config.INSTITUTION_TYPES and t not in types:
            types.append(t)
    reason = (raw.get("relevance_reason") or "").strip() if types else ""
    return types, reason


def reflect(state: dict) -> dict:
    """
    반영: 최종 결정은 코드가 한다.

    순서 (§4)
      목록 밖 claim_id → 인사이트 버림
      근거 claim 이 최소 수 미만 → 인사이트 버림
      상황·반응·본문 중 비어 있는 것 → 인사이트 버림
      본문에 나온 기관 종류가 근거 기관에 없음 → 인사이트 버림
      대처는 하나씩 검사해서 문제 있는 것만 버림
      쓸모 태그는 목록 밖 값만 뺌
    그다음 출처와 기관을 claim 에서 계산한다.
    """
    if state.get("llm_error"):
        return {**state, "insights": []}  # API 에서는 503 으로 바뀐다 (graph.py)

    index = state["claim_index"]
    known_types = set(config.INSTITUTION_TYPES) | {c["institution_type"] for c in state["claims"]}
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
        cited_types = {c["institution_type"] for c in claims}
        if not _institutions_grounded(list(texts.values()), cited_types, known_types):
            continue

        inst_ids = sorted({c["institution_id"] for c in claims})
        relevant_types, relevance_reason = _relevance(raw)

        insights.append(Insight(
            **texts,
            supports=_clean_supports(raw.get("supports"), index, known_types),
            claim_ids=ids,
            relevant_institution_types=relevant_types,
            relevance_reason=relevance_reason,
            source_entry_ids=sorted({e["journal_entry_id"] for c in claims for e in c["evidence"]}),
            source_institution_ids=inst_ids,
            institution_types=sorted(cited_types),
            cross_institution=len(inst_ids) >= 2,
        ))

    if config.MAX_INSIGHTS is not None:
        insights = insights[: config.MAX_INSIGHTS]
    return {**state, "insights": insights}