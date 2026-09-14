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
from .names import find_name_hits
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
    }


# ── ③ llm_judge (B-3 에서는 스텁) ───────────────────────────────


def llm_judge(state: MatchingState) -> dict:
    """
    애매한 경우에만 불린다.

    B-4 에서 Luna 를 호출해 후보 점수를 다시 매기고, 대등 언급 여부를 확정한다.
    지금은 통과만 시킨다 — 그래도 코드로 매긴 점수가 그대로 decide 로 간다.
    """
    return {"llm_called": False}


# ── ④ decide ────────────────────────────────────────────────────


def _evidence_for(state: MatchingState, child_ids: list[int]) -> list[dict]:
    """해당 아이들의 이름이 등장한 구간을 근거로 담는다. 최소 구간만 담는다."""
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

    마지막에 하나 더: 표지 힌트를 뒤집은 경우(hint_mismatch)는 auto 로 내보내지
    않고 review 로 내린다. 표지가 틀렸거나 우리 판단이 틀렸거나 둘 중 하나인데,
    어느 쪽이든 사람이 봐야 한다.
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

    return {
        **base,
        "status": status,
        "matched_child_id": winner,
        "hint_mismatch": hint_mismatch,
        "evidence": _evidence_for(state, [winner]),
    }
