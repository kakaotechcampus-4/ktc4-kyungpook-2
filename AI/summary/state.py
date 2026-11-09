from typing import TypedDict

from .schemas import SourceEntry


class SummaryState(TypedDict, total=False):
    child_id: int
    child_name: str
    entry_date: str
    institution_id: int
    institution_name: str | None
    sources: list[SourceEntry]
    other_child_names: list[str]

    by_id: dict[int, str]

    llm_called: bool
    llm_error: str | None
    llm_failure: str
    llm_usage: dict
    raw_claims: list

    claims: list[dict]
    dropped_evidence: int
    dropped_claims: list[str]
    dropped_detail: list[dict]

    content: str
    covered_entry_ids: list[int]
    uncovered_entry_ids: list[int]
    needs_review: bool
    review_reasons: list[str]
