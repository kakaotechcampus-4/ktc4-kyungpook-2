from .config import (
    ALLOW_AUTO_ON_HINT_ONLY,
    AUTO_GATE,
    CO_MENTION_NEEDS_TWO_NAMES,
    GIVEN_NAME_SCORE,
    IGNORE_UNGROUNDED_LLM_PICK,
    REQUIRE_WINNER_NAME_FOR_AUTO,
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
    UNMATCHED_WITHOUT_ANCHOR,
    UNMATCHED_WHEN_HINT_NOT_IN_ROSTER,
)
from .llm import LlmError, ask_json, spans_for_quotes
from .names import find_name_hits
from .prompts import build_messages
from .state import MatchingState


def extract(state: MatchingState) -> dict:
    hits = find_name_hits(state["content"], state["roster"])
    return {"hits": hits}


def _fuzzy_to_score(ratio: float) -> float:
    span = 1.0 - FUZZY_MIN_RATIO
    return FUZZY_SCORE_MIN + (ratio - FUZZY_MIN_RATIO) / span * (
        FUZZY_SCORE_MAX - FUZZY_SCORE_MIN
    )


def shortlist(state: MatchingState) -> dict:
    hits = state.get("hits", [])
    hint_name = state.get("hint_name")

    best: dict[int, float] = {}
    exact_ids: set[int] = set()
    exact_names: set[str] = set()
    given_groups: dict[str, set[int]] = {}
    fuzzy_ties: dict[tuple, dict[int, float]] = {}

    for hit in hits:
        if hit.exact:
            score = EXACT_SCORE
            exact_ids.add(hit.child_id)
            exact_names.add(hit.name)
        elif hit.given_name:
            score = GIVEN_NAME_SCORE
            given_groups.setdefault(hit.name[1:], set()).add(hit.child_id)
        elif not hit.exact and not hit.given_name:
            score = _fuzzy_to_score(hit.ratio)
            fuzzy_ties.setdefault((hit.start, hit.end), {})[hit.child_id] = hit.ratio
        elif hit.partial:
            score = PARTIAL_NAME_SCORE
        else:
            score = _fuzzy_to_score(hit.ratio)
        best[hit.child_id] = max(best.get(hit.child_id, 0.0), score)

    if hint_name:
        for entry in state["roster"]:
            if entry.name == hint_name and entry.child_id in best:
                best[entry.child_id] = min(
                    SCORE_CAP, best[entry.child_id] + HINT_BONUS
                )

    if hint_name:
        for entry in state["roster"]:
            if entry.name == hint_name:
                best[entry.child_id] = max(
                    best.get(entry.child_id, 0.0), HINT_ONLY_SCORE
                )

    ambiguous_group: set[int] = set()
    if SPLIT_SAME_NAME_CANDIDATES:
        by_id = {e.child_id: e for e in state["roster"]}

        groups: list[set[int]] = []
        same_full: dict[str, set[int]] = {}
        for entry in state["roster"]:
            if entry.child_id in best:
                same_full.setdefault(entry.name, set()).add(entry.child_id)
        groups += [g for g in same_full.values() if len(g) >= 2]
        groups += [
            {cid for cid in g if cid in best}
            for g in given_groups.values()
            if len({cid for cid in g if cid in best}) >= 2
        ]
        for ratios in fuzzy_ties.values():
            if not ratios:
                continue
            top = max(ratios.values())
            tied = {cid for cid, r in ratios.items() if r == top and cid in best}
            if len(tied) >= 2:
                groups.append(tied)

        hint_birthdate = state.get("hint_birthdate")
        for group in groups:
            matched = (
                [c for c in group if by_id[c].birthdate == hint_birthdate]
                if hint_birthdate
                else []
            )
            if len(matched) == 1:
                for cid in group:
                    if cid != matched[0]:
                        best.pop(cid, None)
                        exact_ids.discard(cid)
            else:
                ambiguous_group |= group

    candidates = sorted(
        (
            {"child_id": cid, "confidence": round(s, 4)}
            for cid, s in best.items()
            if s >= TAU_REJECT
        ),
        key=lambda c: c["confidence"],
        reverse=True,
    )

    co_mention = len(exact_names) >= 2

    return {
        "no_textual_anchor": not hits and not hint_name,
        "ambiguous_group": sorted(ambiguous_group),
        "hint_in_roster": bool(hint_name) and any(
            e.name == hint_name for e in state["roster"]
        ),
        "candidates": candidates,
        "mentioned_child_ids": sorted(exact_ids),
        "exact_child_ids": sorted(exact_ids),
        "co_mention": co_mention,
        "has_exact": bool(exact_ids),
    }


def llm_judge(state: MatchingState) -> dict:
    content = state["content"]

    try:
        result = ask_json(build_messages(state))
    except LlmError as exc:
        return {"llm_called": True, "llm_error": str(exc)}

    answer = result.data

    candidates = [dict(c) for c in state.get("candidates", [])]
    mentioned = set(state.get("mentioned_child_ids", []))

    roster_ids = {e.child_id for e in state["roster"]}

    raw_id = answer.get("child_id")
    child_id = (
        int(raw_id)
        if isinstance(raw_id, (int, float)) and not isinstance(raw_id, bool)
        else None
    )

    off_roster = child_id is not None and child_id not in roster_ids
    if off_roster:
        child_id = None

    code_ids = {c["child_id"] for c in state.get("candidates", [])}
    exact_ids = set(state.get("exact_child_ids") or ())
    grounded = child_id is not None and (
        child_id in exact_ids or _hint_confirms(state, child_id)
    )

    ungrounded_pick = False
    if IGNORE_UNGROUNDED_LLM_PICK and child_id is not None and not grounded:
        if exact_ids or child_id not in code_ids:
            ungrounded_pick = bool(exact_ids)
            child_id = None

    try:
        llm_confidence = float(answer.get("confidence", 0.0))
    except (TypeError, ValueError):
        llm_confidence = 0.0
    llm_confidence = max(0.0, min(SCORE_CAP, llm_confidence))

    if child_id is None:
        if ungrounded_pick:
            kept = [c for c in candidates if c["child_id"] in exact_ids]
            if kept:
                candidates = kept
    else:
        twins = set(state.get("ambiguous_group") or ()) | _same_name_ids(state, child_id)
        twins.discard(child_id)
        if SPLIT_SAME_NAME_CANDIDATES and child_id in set(
            state.get("ambiguous_group") or ()
        ) or (SPLIT_SAME_NAME_CANDIDATES and twins and _same_name_ids(state, child_id)):
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

        code_scores = {
            c["child_id"]: c["confidence"] for c in state.get("candidates", [])
        }
        score = llm_confidence
        if COMBINE_AGREEING_SCORES and child_id in code_scores:
            score = max(score, code_scores[child_id])
        candidates = [{"child_id": child_id, "confidence": round(score, 4)}]

    co_ids = [
        int(cid)
        for cid in (answer.get("co_mention_child_ids") or [])
        if isinstance(cid, (int, float))
        and not isinstance(cid, bool)
        and int(cid) in roster_ids
    ]
    co_mention = bool(answer.get("co_mention", False))
    if CO_MENTION_NEEDS_TWO_NAMES:
        if not state.get("co_mention"):
            co_mention = False
            co_ids = []
        else:
            exact = set(state.get("exact_child_ids", []))
            co_ids = [cid for cid in co_ids if cid in exact]
    mentioned.update(co_ids)

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
        "llm_ungrounded_pick": ungrounded_pick,
        "candidates": candidates,
        "mentioned_child_ids": sorted(mentioned),
        "co_mention": co_mention,
        "llm_evidence": spans_for_quotes(content, answer.get("quotes")),
    }


def _same_name_ids(state: MatchingState, child_id: int) -> set[int]:
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
    hint = state.get("hint_birthdate")
    if not hint:
        return None
    matched = [
        e.child_id
        for e in state["roster"]
        if e.child_id in ids and e.birthdate == hint
    ]
    return matched[0] if len(matched) == 1 else None


def _evidence_for(state: MatchingState, child_ids: list[int]) -> list[dict]:
    llm_spans = state.get("llm_evidence") or []
    if llm_spans:
        return llm_spans

    return [
        {"start": h.start, "end": h.end}
        for h in state.get("hits", [])
        if h.child_id in child_ids
    ]


def _hint_confirms(state: MatchingState, winner: int) -> bool:
    hint_name = state.get("hint_name")
    if not hint_name:
        return False

    same_name = [e for e in state["roster"] if e.name == hint_name]
    if len(same_name) != 1:
        return False

    return same_name[0].child_id == winner


def _structural_auto(state: MatchingState, winner: int, candidates: list) -> bool:
    if len(candidates) != 1:
        return False
    if state.get("co_mention"):
        return False
    if state.get("llm_ungrounded_pick"):
        return False
    if state.get("llm_error") or state.get("llm_off_roster"):
        return False
    if _hint_confirms(state, winner):
        return True
    if not REQUIRE_WINNER_NAME_FOR_AUTO:
        return bool(state.get("has_exact"))
    return winner in set(state.get("exact_child_ids") or ())


def decide(state: MatchingState) -> dict:
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

    if UNMATCHED_WITHOUT_ANCHOR and state.get("no_textual_anchor"):
        return {**base, "status": "unmatched", "confidence": 0.0}

    if (
        UNMATCHED_WHEN_HINT_NOT_IN_ROSTER
        and state.get("hint_name")
        and not state.get("hint_in_roster")
        and not state.get("has_exact")
    ):
        return {**base, "status": "unmatched"}

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

    if AUTO_GATE == "structural":
        auto_ok = _structural_auto(state, winner, candidates)
    else:
        auto_ok = top >= TAU_AUTO and gap_ok

    if auto_ok:
        status = "auto"
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
    else:
        return {**base, "status": "unmatched"}

    hint_name = state.get("hint_name")
    hint_mismatch = False
    if hint_name:
        matched = next(
            (e for e in state["roster"] if e.child_id == winner), None
        )
        hint_mismatch = matched is not None and matched.name != hint_name

    if hint_mismatch and status == "auto":
        status = "review"

    if state.get("llm_off_roster") and status == "auto":
        status = "review"

    if state.get("llm_error") and status == "auto":
        status = "review"

    winner_named = winner in set(state.get("exact_child_ids") or ())
    if not winner_named and status == "auto":
        if not (ALLOW_AUTO_ON_HINT_ONLY and _hint_confirms(state, winner)):
            status = "review"

    return {
        **base,
        "status": status,
        "matched_child_id": winner,
        "hint_mismatch": hint_mismatch,
        "evidence": _evidence_for(state, [winner]),
    }
