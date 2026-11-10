from typing import Literal

from pydantic import BaseModel, Field


class EvidenceSentence(BaseModel):
    """근거 일지에서 이 글이 실제로 인용한 문장 하나. 보호자 리포트 화면과 같은 값이다."""

    text: str
    role: str


class EvidenceEntry(BaseModel):
    """근거 일지 한 건. 화면의 "이 글의 근거" 한 줄과 같다."""

    institution_name: str
    date: str
    looked_up: bool = False
    sentences: list[EvidenceSentence] = Field(default_factory=list)


class ApprovedInsight(BaseModel):
    """
    Gate 2 에서 승인된 인사이트 하나.

    **보호자 리포트 화면(G-36)과 같은 값을 받는다.** 메일과 화면이 다른 글이면 보호자가
    둘을 견주다 헷갈린다 — 같은 것을 두 곳에서 보여 주는 것이다.
    """

    content: str

    institution_name: str = ""

    period_label: str = ""

    institution_types: list[str] = Field(default_factory=list)

    cross_institution: bool = False

    evidence: list[EvidenceEntry] = Field(default_factory=list)


class NotifyInput(BaseModel):
    """
    Gate 2 승인 직후 BE 가 보낸다.

    ⚠️ 보호자 이메일은 여기 없다. BE 는 child_id 만 준다 (#420 이 끝나면 MCP 서버가 BE 에 묻는다).
    """

    child_id: int
    child_name: str
    period_from: str
    period_to: str

    insights: list[ApprovedInsight] = Field(default_factory=list)

    notification_id: str

    other_child_names: list[str] = Field(default_factory=list)


NotifyStatus = Literal["sent", "no_recipient", "blocked", "nothing_to_send"]


class BlockedLine(BaseModel):
    """검사에 걸린 줄 하나. 교사 화면이 "왜 안 나갔나" 를 보여 주는 재료다."""

    text: str
    reasons: list[str]


class NotifyOutput(BaseModel):
    child_id: int
    notification_id: str
    status: NotifyStatus

    message_id: str | None = None

    duplicate: bool = False

    blocked_lines: list[BlockedLine] = Field(default_factory=list)

