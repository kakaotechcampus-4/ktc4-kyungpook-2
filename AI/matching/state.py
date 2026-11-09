from typing import TypedDict

from .names import NameHit
from .schemas import RosterEntry


class ScoredCandidate(TypedDict):
    child_id: int
    confidence: float


class MatchingState(TypedDict, total=False):
    journal_entry_id: int
    content: str
    roster: list[RosterEntry]
    hint_name: str | None
    hint_birthdate: str | None

    hits: list[NameHit]

    candidates: list[ScoredCandidate]
    mentioned_child_ids: list[int]
    co_mention: bool
    has_exact: bool
    exact_child_ids: list[int]
    hint_in_roster: bool
    no_textual_anchor: bool
    ambiguous_group: list[int]

    llm_called: bool
    llm_evidence: list[dict]
    llm_error: str | None
    llm_off_roster: bool
    llm_ungrounded_pick: bool
    llm_usage: dict

    status: str
    matched_child_id: int | None
    confidence: float
    hint_mismatch: bool
    evidence: list[dict]
    multi_reason: str | None
