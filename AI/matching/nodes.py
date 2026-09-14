"""
매칭 그래프의 노드 넷.

    extract → shortlist →(조건부)→ llm_judge → decide

핵심은 llm_judge 를 "언제 부르지 않는가" 다.
이름이 그대로 적힌 기록은 코드로 끝내고, 애매한 것만 모델에 넘긴다.
기획서 리스크 #4(모델 호출 지점 최소화)에 대한 실제 대응이다.
"""

from .config import (
    EXACT_SCORE,
    FUZZY_MIN_RATIO,
    HINT_BONUS,
    HINT_ONLY_SCORE,
    SCORE_CAP,
    TAU_AUTO,
    TAU_GAP,
    TAU_REJECT,
)
from .llm import LlmError, ask_json, spans_for_quotes
from .names import find_name_hits
from .prompts import build_messages
from .state import MatchingState


# ── ① extract ───────────────────────────────────────────────────


def extract(state: MatchingState) -> dict:
    """본문에서 명부에 있는 이름이 등장하는 지점을 모두 찾는다. LLM 을 쓰지 않는다."""
    hits = find_name_hits(state["content"], state["roster"])
    return {"hits": hits}


# ── ② shortlist ─────────────────────────────────────────────────


def _fuzzy_to_score(ratio: float) -> float:
    """
    편집거리 유사도를 후보 점수로 옮긴다.

    오타로 걸린 건 자동 확정(TAU_AUTO)에 닿지 않고 사람 확인(review) 구간에
    떨어져야 한다. [FUZZY_MIN_RATIO, 1.0] 을 [0.62, 0.88] 로 선형 변환한다.
    """
    span = 1.0 - FUZZY_MIN_RATIO
    return 0.62 + (ratio - FUZZY_MIN_RATIO) / span * 0.26


def shortlist(state: MatchingState) -> dict:
    """발견 지점을 아이별 점수로 집계하고, 표지 힌트를 약하게 반영한다."""
    hits = state.get("hits", [])
    hint_name = state.get("hint_name")

    best: dict[int, float] = {}
    exact_ids: set[int] = set()

    for hit in hits:
        score = EXACT_SCORE if hit.exact else _fuzzy_to_score(hit.ratio)
        if hit.exact:
            exact_ids.add(hit.child_id)
        best[hit.child_id] = max(best.get(hit.child_id, 0.0), score)

    # 표지 힌트는 정답이 아니라 단서다. 순위를 뒤집지 못할 만큼만 얹는다.
    if hint_name:
        for entry in state["roster"]:
            if entry.name == hint_name and entry.child_id in best:
                best[entry.child_id] = min(
                    SCORE_CAP, best[entry.child_id] + HINT_BONUS
                )

    # 본문에 이름이 전혀 없고 표지 힌트만 있는 경우.
    # 자동 확정에는 못 미치고 사람 확인으로 가는 점수를 준다.
    if not best and hint_name:
        for entry in state["roster"]:
            if entry.name == hint_name:
                best[entry.child_id] = HINT_ONLY_SCORE

    candidates = sorted(
        ({"child_id": cid, "confidence": round(s, 4)} for cid, s in best.items()),
        key=lambda c: c["confidence"],
        reverse=True,
    )

    # 서로 다른 아이 이름이 둘 이상 그대로 등장하면 대등 언급을 의심한다.
    # 이 단계에서는 "의심"까지만 하고, 실제로 두 아이의 행동이 대등하게
    # 기술되었는지는 B-4 에서 llm_judge 가 확정한다.
    co_mention = len(exact_ids) >= 2

    return {
        "candidates": candidates,
        # "본문에 이름이 등장했다" 는 사실만 담는다. 오타로 걸린 것은 넣지 않는다 —
        # 실제로 등장한 게 아니라 비슷했을 뿐이라, Validation 에 잘못된 신호를 준다.
        "mentioned_child_ids": sorted(exact_ids),
        "co_mention": co_mention,
        "has_exact": bool(exact_ids),
    }


# ── ③ llm_judge (B-3 에서는 스텁) ───────────────────────────────


def llm_judge(state: MatchingState) -> dict:
    """
    애매한 경우에만 불린다. 모델이 후보를 다시 매기고 대등 언급 여부를 확정한다.

    호출이 실패해도 기록을 버리지 않는다. 코드가 매긴 점수를 그대로 두고
    llm_error 만 남기면, decide 가 그 경우 auto 로 내보내지 않는다.
    애매해서 부른 것이라 코드 점수는 어차피 review/multi 구간에 있고,
    결과적으로 사람 확인 큐로 간다.
    """
    content = state["content"]

    try:
        result = ask_json(build_messages(state))
    except LlmError as exc:
        return {"llm_called": True, "llm_error": str(exc)}

    answer = result.data

    candidates = [dict(c) for c in state.get("candidates", [])]
    mentioned = set(state.get("mentioned_child_ids", []))

    raw_id = answer.get("child_id")
    child_id = int(raw_id) if isinstance(raw_id, (int, float)) else None

    try:
        llm_confidence = float(answer.get("confidence", 0.0))
    except (TypeError, ValueError):
        llm_confidence = 0.0
    llm_confidence = max(0.0, min(SCORE_CAP, llm_confidence))

    if child_id is None:
        # 모델이 특정하지 못했다. 코드가 본 후보를 그대로 두고 사람에게 넘긴다.
        # 후보가 둘이면 multi(ambiguous_identity), 하나면 review 로 간다.
        pass
    else:
        # 모델이 하나를 골랐으면 그것이 답이다 — "확신이 없으면 null" 이라고
        # 지시했으므로, 골랐다는 것은 판단이 섰다는 뜻이다.
        # 나머지 후보를 점수째로 남겨두면 코드 점수가 모델 판단을 역전시킨다.
        # 다른 아이들은 mentioned_child_ids 에 남아 Validation 으로 전달되므로
        # 정보가 사라지지도 않는다.
        candidates = [{"child_id": child_id, "confidence": round(llm_confidence, 4)}]

    co_ids = [
        int(cid)
        for cid in (answer.get("co_mention_child_ids") or [])
        if isinstance(cid, (int, float))
    ]
    mentioned.update(co_ids)

    return {
        "llm_called": True,
        "llm_error": None,
        "llm_usage": result.usage,
        "candidates": candidates,
        "mentioned_child_ids": sorted(mentioned),
        # 대등 언급 여부는 모델 판단으로 덮는다. 코드는 "이름이 둘 이상 있다" 까지만
        # 알 수 있고, 각자 행동했는지는 문맥을 읽어야 안다.
        "co_mention": bool(answer.get("co_mention", False)),
        "llm_evidence": spans_for_quotes(content, answer.get("quotes")),
    }


# ── ④ decide ────────────────────────────────────────────────────


def _evidence_for(state: MatchingState, child_ids: list[int]) -> list[dict]:
    """
    판정 근거 구간. 최소 구간만 담는다.

    모델이 인용한 것이 있으면 그것을 쓴다 — 이름이 적힌 위치보다
    "왜 그렇게 봤는지" 를 더 잘 가리킨다. 없으면 이름이 등장한 위치로 대신한다.
    """
    llm_spans = state.get("llm_evidence") or []
    if llm_spans:
        return llm_spans

    return [
        {"start": h.start, "end": h.end}
        for h in state.get("hits", [])
        if h.child_id in child_ids
    ]


def decide(state: MatchingState) -> dict:
    """
    status 결정 규칙을 그대로 옮긴 것. 위에서부터 먼저 걸리는 조건을 적용한다.

        1. 대등 언급           → multi (co_mention)
        2. 최고점 ≥ τ_auto AND 격차 ≥ τ_gap → auto
        3. 최고점 ≥ τ_reject, 후보 1명       → review
        4. 최고점 ≥ τ_reject, 후보 2명 이상  → multi (ambiguous_identity)
        5. 그 외                              → unmatched

    마지막에 두 가지 더. 아래 경우는 auto 로 내보내지 않고 review 로 내린다.
      - 표지 힌트를 뒤집은 경우(hint_mismatch) — 표지가 틀렸거나 우리가 틀렸거나
        둘 중 하나인데, 어느 쪽이든 사람이 봐야 한다.
      - 모델 호출이 실패한 경우(llm_error) — 판단 근거가 반쪽이다.
      - 본문에 이름이 그대로 없는 경우(has_exact=False) — 오타·별명 추론이라
        모델 confidence 를 믿을 수 없다.
    """
    candidates = state.get("candidates", [])
    top = candidates[0]["confidence"] if candidates else 0.0
    second = candidates[1]["confidence"] if len(candidates) > 1 else None

    base = {
        "confidence": top,
        "matched_child_id": None,
        "multi_reason": None,
        "evidence": [],
        "hint_mismatch": False,
    }

    # 1. 대등 언급 — confidence 가 높아도 사람에게 넘긴다.
    #    한 항목을 여러 건으로 쪼개는 것은 MVP 범위가 아니라, 보류를 택한다.
    if state.get("co_mention"):
        ids = [c["child_id"] for c in candidates]
        return {
            **base,
            "status": "multi",
            "multi_reason": "co_mention",
            "evidence": _evidence_for(state, ids),
        }

    if not candidates:
        return {**base, "status": "unmatched", "confidence": 0.0}

    winner = candidates[0]["child_id"]
    gap_ok = second is None or (top - second) >= TAU_GAP

    # 2. 자동 확정
    if top >= TAU_AUTO and gap_ok:
        status = "auto"
    # 3~4. 사람 확인
    elif top >= TAU_REJECT:
        if len(candidates) == 1:
            status = "review"
        else:
            return {
                **base,
                "status": "multi",
                "multi_reason": "ambiguous_identity",
                "evidence": _evidence_for(
                    state, [c["child_id"] for c in candidates]
                ),
            }
    # 5. 후보 없음
    else:
        return {**base, "status": "unmatched"}

    # 표지 힌트와 다른 아이로 판단했는지
    hint_name = state.get("hint_name")
    hint_mismatch = False
    if hint_name:
        matched = next(
            (e for e in state["roster"] if e.child_id == winner), None
        )
        hint_mismatch = matched is not None and matched.name != hint_name

    # 표지를 뒤집었으면 자동 확정하지 않는다
    if hint_mismatch and status == "auto":
        status = "review"

    # 모델 호출이 실패했으면 코드 점수만으로 자동 확정하지 않는다.
    # 애매해서 부른 것이라, 판단 근거가 반쪽인 채로 통과시키면 안 된다.
    if state.get("llm_error") and status == "auto":
        status = "review"

    # 본문에 이름이 그대로 적힌 아이가 하나도 없으면 자동 확정하지 않는다.
    #
    # 오타("임유젼")나 별명("막내가")만 있는 경우인데, 모델이 높은 confidence 를
    # 주더라도 그 숫자를 믿을 수 없다. 실제로 "임유젼"(임유진/임유전 어느 쪽의
    # 오타인지 편집거리상 완전 동점)에 모델이 0.98 을 준 사례가 있었다.
    # 모델은 자기 확신도를 캘리브레이션하지 못한다.
    if not state.get("has_exact") and status == "auto":
        status = "review"

    return {
        **base,
        "status": status,
        "matched_child_id": winner,
        "hint_mismatch": hint_mismatch,
        "evidence": _evidence_for(state, [winner]),
    }
