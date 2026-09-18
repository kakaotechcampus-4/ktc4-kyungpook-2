"""
매칭 그래프의 노드 넷.

    extract → shortlist →(조건부)→ llm_judge → decide

핵심은 llm_judge 를 "언제 부르지 않는가" 다.
이름이 그대로 적힌 기록은 코드로 끝내고, 애매한 것만 모델에 넘긴다.
기획서 리스크 #4(모델 호출 지점 최소화)에 대한 실제 대응이다.
"""

from .config import (
    ALLOW_AUTO_ON_HINT_ONLY,
    FUZZY_SCORE_MAX,
    FUZZY_SCORE_MIN,
    COMBINE_AGREEING_SCORES,
    EXACT_SCORE,
    FUZZY_MIN_RATIO,
    HINT_BONUS,
    HINT_ONLY_SCORE,
    PARTIAL_NAME_SCORE,
    SPLIT_SAME_NAME_CANDIDATES,
    SCORE_CAP,
    TAU_AUTO,
    TAU_GAP,
    TAU_REJECT,
    UNMATCHED_WHEN_HINT_NOT_IN_ROSTER,
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

    퍼지는 "비슷했을 뿐" 이라 표지 힌트보다 약한 근거다. 한 글자 차이(0.667)가
    HINT_ONLY_SCORE 아래에 오도록 구간을 잡는다. 그래야 문장 조각에서 나온
    오탐이 표지를 이기지 못한다.
    """
    span = 1.0 - FUZZY_MIN_RATIO
    return FUZZY_SCORE_MIN + (ratio - FUZZY_MIN_RATIO) / span * (
        FUZZY_SCORE_MAX - FUZZY_SCORE_MIN
    )


def shortlist(state: MatchingState) -> dict:
    """발견 지점을 아이별 점수로 집계하고, 표지 힌트를 약하게 반영한다."""
    hits = state.get("hits", [])
    hint_name = state.get("hint_name")

    best: dict[int, float] = {}
    exact_ids: set[int] = set()
    #: 정확 일치로 등장한 "이름" 들. child_id 가 아니라 이름으로 세는 것이
    #: 중요하다 — 동명이인이면 이름 하나에 child_id 가 둘 붙는데, 그것은
    #: 두 아이가 언급된 것이 아니라 누군지 모르는 것이다.
    exact_names: set[str] = set()

    for hit in hits:
        if hit.exact:
            score = EXACT_SCORE
            exact_ids.add(hit.child_id)
            exact_names.add(hit.name)
        elif hit.partial:
            # 이름이 단어의 일부로만 들어 있었다 ('은하수' 안의 '은하').
            # 후보로는 남기되 EXACT_SCORE 를 주지 않아, LLM 건너뛰기 경로에
            # 들어가지 못하고 사람 확인으로 간다.
            score = PARTIAL_NAME_SCORE
        else:
            score = _fuzzy_to_score(hit.ratio)
        best[hit.child_id] = max(best.get(hit.child_id, 0.0), score)

    # 표지 힌트는 정답이 아니라 단서다. 순위를 뒤집지 못할 만큼만 얹는다.
    if hint_name:
        for entry in state["roster"]:
            if entry.name == hint_name and entry.child_id in best:
                best[entry.child_id] = min(
                    SCORE_CAP, best[entry.child_id] + HINT_BONUS
                )

    # 표지 힌트가 가리키는 아이는 언제나 바닥 점수를 받는다.
    # "본문에 이름이 없을 때만" 으로 두면, 엉뚱한 아이가 퍼지로 하나 걸렸다는
    # 이유로 표지 근거가 통째로 사라진다.
    if hint_name:
        for entry in state["roster"]:
            if entry.name == hint_name:
                best[entry.child_id] = max(
                    best.get(entry.child_id, 0.0), HINT_ONLY_SCORE
                )

    # 동명이인 정리. 같은 이름이 명부에 둘 이상이면 본문만으로는 구별할
    # 방법이 없다. 표지의 생년월일로 한 명이 특정될 때만 좁히고, 아니면 둘 다
    # 남겨 사람이 고르게 한다(decide 가 multi 로 낸다).
    if SPLIT_SAME_NAME_CANDIDATES:
        by_name: dict[str, list] = {}
        for entry in state["roster"]:
            if entry.child_id in best:
                by_name.setdefault(entry.name, []).append(entry)

        hint_birthdate = state.get("hint_birthdate")
        for group in by_name.values():
            if len(group) < 2:
                continue
            matched = [e for e in group if e.birthdate == hint_birthdate] if hint_birthdate else []
            if len(matched) != 1:
                continue
            for entry in group:
                if entry.child_id != matched[0].child_id:
                    best.pop(entry.child_id, None)
                    exact_ids.discard(entry.child_id)

    # TAU_REJECT 미만은 후보로 치지 않는다. 문장 조각에서 나온 퍼지 오탐이
    # 여기서 떨어져, 애먼 아이가 교사에게 선택지로 올라가지 않는다.
    candidates = sorted(
        (
            {"child_id": cid, "confidence": round(s, 4)}
            for cid, s in best.items()
            if s >= TAU_REJECT
        ),
        key=lambda c: c["confidence"],
        reverse=True,
    )

    # 서로 다른 "이름" 이 둘 이상 등장했을 때만 대등 언급을 의심한다.
    # 동명이인이라 child_id 가 둘인 경우는 여기 해당하지 않는다 — 같은 이름
    # 하나가 나온 것이고, 누구인지 모르는 상태다.
    # 이 단계에서는 "의심"까지만 하고, 실제로 두 아이의 행동이 대등하게
    # 기술되었는지는 B-4 에서 llm_judge 가 확정한다.
    co_mention = len(exact_names) >= 2

    return {
        "hint_in_roster": bool(hint_name) and any(
            e.name == hint_name for e in state["roster"]
        ),
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

    # 모델 응답은 전부 이번 요청의 명부로 검증한다.
    # 프롬프트에 "명부에서만 고른다" 고 적어두었지만 그것은 지시일 뿐이고,
    # 지켜졌는지는 코드가 확인해야 한다. 명부에 없는 ID 를 그대로 흘리면
    # 존재하지 않는 아동에게 기록이 붙는다.
    roster_ids = {e.child_id for e in state["roster"]}

    raw_id = answer.get("child_id")
    # bool 은 int 의 하위 타입이라 True 가 1 로 통과한다. 먼저 걸러낸다.
    child_id = (
        int(raw_id)
        if isinstance(raw_id, (int, float)) and not isinstance(raw_id, bool)
        else None
    )

    off_roster = child_id is not None and child_id not in roster_ids
    if off_roster:
        # 판단을 버리고 코드 후보로 되돌린다. decide 가 auto 로 내보내지 않는다.
        child_id = None

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
        # 동명이인이 있으면 모델이 한 명을 골랐어도 그것은 판단이 아니라
        # 임의 선택이다. 이름이 같으면 본문만으로 구별할 방법이 없다.
        # 생년월일로 한 명이 특정될 때만 좁히고, 아니면 둘 다 남겨 multi 로 보낸다.
        twins = _same_name_ids(state, child_id)
        if SPLIT_SAME_NAME_CANDIDATES and twins:
            resolved = _resolve_by_birthdate(state, twins | {child_id})
            if resolved is None:
                code_scores = {
                    c["child_id"]: c["confidence"]
                    for c in state.get("candidates", [])
                }
                return {
                    "llm_called": True,
                    "llm_error": None,
                    "llm_usage": result.usage,
                    # 점수를 같게 줘서 어느 쪽도 앞서지 않게 한다.
                    # decide 가 격차 부족으로 multi(ambiguous_identity) 를 낸다.
                    "candidates": [
                        {
                            "child_id": cid,
                            "confidence": round(
                                max(llm_confidence, code_scores.get(cid, 0.0)), 4
                            ),
                        }
                        for cid in sorted(twins | {child_id})
                    ],
                    "mentioned_child_ids": sorted(mentioned),
                    "co_mention": False,
                    "llm_off_roster": off_roster,
                    "llm_evidence": spans_for_quotes(content, answer.get("quotes")),
                }
            child_id = resolved

        # 단, 코드가 이미 같은 아이를 후보로 봤다면 둘 중 높은 쪽을 쓴다.
        # 본문에 이름이 없는 기록에서 모델은 "본문만으로는 확신 못 한다"는 뜻으로
        # 낮은 값을 주는데, 그것으로 표지 근거까지 지워버리면 안 된다.
        code_scores = {
            c["child_id"]: c["confidence"] for c in state.get("candidates", [])
        }
        score = llm_confidence
        if COMBINE_AGREEING_SCORES and child_id in code_scores:
            score = max(score, code_scores[child_id])
        candidates = [{"child_id": child_id, "confidence": round(score, 4)}]

    # 대등 언급 목록도 명부에 있는 ID 만 받는다.
    co_ids = [
        int(cid)
        for cid in (answer.get("co_mention_child_ids") or [])
        if isinstance(cid, (int, float))
        and not isinstance(cid, bool)
        and int(cid) in roster_ids
    ]
    mentioned.update(co_ids)

    co_mention = bool(answer.get("co_mention", False))

    # 대등 언급이면 후보를 하나로 좁히면 안 된다.
    # "누가 주인공인지 모르겠다" 가 아니라 "둘 다 나온다" 는 상태라,
    # 화면이 교사에게 두 아이를 다 보여주고 고르게 해야 한다.
    if co_mention:
        known_ids = {c["child_id"] for c in candidates}
        code_scores = {c["child_id"]: c["confidence"] for c in state.get("candidates", [])}
        for cid in co_ids:
            if cid in known_ids:
                continue
            candidates.append(
                {"child_id": cid, "confidence": code_scores.get(cid, llm_confidence)}
            )
        candidates.sort(key=lambda c: c["confidence"], reverse=True)

    return {
        "llm_called": True,
        "llm_error": None,
        "llm_usage": result.usage,
        "llm_off_roster": off_roster,
        "candidates": candidates,
        "mentioned_child_ids": sorted(mentioned),
        # 대등 언급 여부는 모델 판단으로 덮는다. 코드는 "이름이 둘 이상 있다" 까지만
        # 알 수 있고, 각자 행동했는지는 문맥을 읽어야 안다.
        "co_mention": co_mention,
        "llm_evidence": spans_for_quotes(content, answer.get("quotes")),
    }


def _same_name_ids(state: MatchingState, child_id: int) -> set[int]:
    """이 아이와 이름이 같은 명부의 다른 아이들."""
    target = next(
        (e.name for e in state["roster"] if e.child_id == child_id), None
    )
    if target is None:
        return set()
    return {
        e.child_id
        for e in state["roster"]
        if e.name == target and e.child_id != child_id
    }


def _resolve_by_birthdate(state: MatchingState, ids: set[int]) -> int | None:
    """
    표지의 생년월일로 동명이인 중 한 명이 특정되는지.

    정확히 한 명만 걸릴 때 그 아이를 돌려준다. 아무도 안 걸리거나 둘 이상이
    걸리면 구분 근거가 없다는 뜻이라 None 을 돌려준다.
    """
    hint = state.get("hint_birthdate")
    if not hint:
        return None
    matched = [
        e.child_id
        for e in state["roster"]
        if e.child_id in ids and e.birthdate == hint
    ]
    return matched[0] if len(matched) == 1 else None


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


def _hint_confirms(state: MatchingState, winner: int) -> bool:
    """
    표지 힌트가 이 판정을 뒷받침하는가.

    본문에 이름이 없을 때 자동 확정을 허용할지 판단하는 유일한 근거다.
    표지가 가리키는 아이와 판정이 같고, 그 이름이 명부에서 한 명으로
    특정될 때만 참이다. 동명이인이 있으면 표지만으로는 못 고른다.
    """
    hint_name = state.get("hint_name")
    if not hint_name:
        return False

    same_name = [e for e in state["roster"] if e.name == hint_name]
    if len(same_name) != 1:
        return False

    return same_name[0].child_id == winner


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
      - 본문에 이름이 그대로 없고(has_exact=False) 표지 힌트도 뒷받침하지
        않는 경우 — 오타·별명 추론이라 모델 confidence 를 믿을 수 없다.
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

    # 0. 표지 이름이 명부에 없고 본문에도 명부 아이 이름이 그대로 없다.
    #    아직 등록되지 않은 아이의 기록으로 본다. 여기서 끊지 않으면
    #    비슷한 이름들이 퍼지로 걸려 multi 가 되고, 교사에게 전부 오답인
    #    선택지를 보여주게 된다.
    if (
        UNMATCHED_WHEN_HINT_NOT_IN_ROSTER
        and state.get("hint_name")
        and not state.get("hint_in_roster")
        and not state.get("has_exact")
    ):
        return {**base, "status": "unmatched"}

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

    # 모델이 명부에 없는 ID 를 돌려줬으면 자동 확정하지 않는다.
    # 응답 하나가 규칙을 어겼다는 뜻이라, 같은 응답의 다른 값도 믿을 수 없다.
    if state.get("llm_off_roster") and status == "auto":
        status = "review"

    # 모델 호출이 실패했으면 코드 점수만으로 자동 확정하지 않는다.
    # 애매해서 부른 것이라, 판단 근거가 반쪽인 채로 통과시키면 안 된다.
    if state.get("llm_error") and status == "auto":
        status = "review"

    # 본문에 이름이 그대로 적힌 아이가 없는 경우.
    #
    # 모델 confidence 만으로는 통과시키지 않는다. "임유젼"(임유진/임유전 어느
    # 쪽의 오타인지 편집거리상 완전 동점)에 모델이 0.98 을 준 사례가 있었다.
    # 모델은 자기 확신도를 캘리브레이션하지 못한다.
    #
    # 다만 표지 힌트가 같은 아이를 가리키면 통과시킨다. 본문에 이름이 없는 것은
    # 관찰일지의 정상적인 형태이고(파일 표지에 이름, 본문에는 관찰 내용만),
    # 이때 표지를 안 믿으면 확인 큐가 100% 가 되어 서비스가 성립하지 않는다.
    #
    # 표지를 뒤집는 신호는 위에서 이미 걸러졌다 —
    # 본문에 다른 아이 이름이 그대로 있으면 has_exact 가 참이라 여기 오지 않고,
    # 오타로라도 다른 아이가 1위면 hint_mismatch 로 review 가 된다.
    if not state.get("has_exact") and status == "auto":
        if not (ALLOW_AUTO_ON_HINT_ONLY and _hint_confirms(state, winner)):
            status = "review"

    return {
        **base,
        "status": status,
        "matched_child_id": winner,
        "hint_mismatch": hint_mismatch,
        "evidence": _evidence_for(state, [winner]),
    }
