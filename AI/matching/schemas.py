from typing import Literal

from pydantic import BaseModel, Field

MatchStatus = Literal["auto", "review", "multi", "unmatched"]

MultiReason = Literal["ambiguous_identity", "co_mention"]


class RosterEntry(BaseModel):

    child_id: int
    name: str
    birthdate: str


class MatchingInput(BaseModel):
    journal_entry_id: int
    content: str
    roster: list[RosterEntry]

    raw_record_id: int | None = None
    entry_date: str | None = None

    hint_name: str | None = None
    hint_birthdate: str | None = None


class EvidenceSpan(BaseModel):

    start: int
    end: int


class Candidate(BaseModel):
    child_id: int
    confidence: float


class MatchingOutput(BaseModel):
    journal_entry_id: int
    status: MatchStatus

    matched_child_id: int | None = None

    confidence: float = 0.0

    hint_mismatch: bool = False

    evidence: list[EvidenceSpan] = Field(default_factory=list)

    mentioned_child_ids: list[int] = Field(default_factory=list)

    multi_reason: MultiReason | None = None

    candidates: list[Candidate] = Field(default_factory=list)

    llm_called: bool = False
