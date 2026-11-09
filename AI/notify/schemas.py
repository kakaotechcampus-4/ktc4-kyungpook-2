from typing import Literal

from pydantic import BaseModel, Field


class EvidenceSentence(BaseModel):

    text: str
    role: str


class EvidenceEntry(BaseModel):

    institution_name: str
    date: str
    looked_up: bool = False
    sentences: list[EvidenceSentence] = Field(default_factory=list)


class ApprovedInsight(BaseModel):

    content: str

    institution_name: str = ""

    period_label: str = ""

    institution_types: list[str] = Field(default_factory=list)

    cross_institution: bool = False

    evidence: list[EvidenceEntry] = Field(default_factory=list)


class NotifyInput(BaseModel):

    child_id: int
    child_name: str
    period_from: str
    period_to: str

    insights: list[ApprovedInsight] = Field(default_factory=list)

    notification_id: str

    other_child_names: list[str] = Field(default_factory=list)


NotifyStatus = Literal["sent", "no_recipient", "blocked", "nothing_to_send"]


class BlockedLine(BaseModel):

    text: str
    reasons: list[str]


class NotifyOutput(BaseModel):
    child_id: int
    notification_id: str
    status: NotifyStatus

    message_id: str | None = None

    duplicate: bool = False

    blocked_lines: list[BlockedLine] = Field(default_factory=list)

