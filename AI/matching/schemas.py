from typing import Literal

from pydantic import BaseModel, Field

MatchStatus = Literal["auto", "review", "multi", "unmatched"]

MultiReason = Literal["ambiguous_identity", "co_mention"]


class RosterEntry(BaseModel):
    """매칭 후보가 될 수 있는 등록 아동 한 명."""

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
    """
    판정 근거가 된 본문 구간.

    start / end 는 Python str 인덱스(유니코드 코드포인트) 기준이다.
    프론트에서 하이라이트할 때는 String.slice 가 아니라
    Array.from(content).slice(start, end).join('') 로 복원해야 한다.
    (이모지가 섞이면 JavaScript 의 인덱스가 밀린다)
    """

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
