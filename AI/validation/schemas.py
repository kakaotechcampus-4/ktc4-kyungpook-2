# AI/validation/schemas.py
from pydantic import BaseModel
from typing import Literal


class ValidationInput(BaseModel):
    journal_entry_id: int
    content: str
    subject_child_id: int | None = None
    subject_name: str | None = None


class Evidence(BaseModel):
    start: int
    end: int


class Issue(BaseModel):
    """사유 하나와 그 근거 구간의 짝 (#177).

    BE 가 요약에 실제로 들어간 구간의 사유만 골라 보여주려면, 어느 구간이
    어느 사유인지 알아야 한다. 위치가 없는 사유(대상불명확·모델호출실패)는
    start/end 를 비운다.
    """
    type: str
    start: int | None = None
    end: int | None = None


class ValidationOutput(BaseModel):
    journal_entry_id: int
    verdict: Literal["PASS", "REVIEW", "BLOCK"]
    issue_types: list[str] = []
    evidence: list[Evidence] = []
    issues: list[Issue] = []
