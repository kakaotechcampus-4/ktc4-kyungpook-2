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
    type: str
    start: int | None = None
    end: int | None = None


class ValidationOutput(BaseModel):
    journal_entry_id: int
    verdict: Literal["PASS", "REVIEW", "BLOCK"]
    issue_types: list[str] = []
    evidence: list[Evidence] = []
    issues: list[Issue] = []
