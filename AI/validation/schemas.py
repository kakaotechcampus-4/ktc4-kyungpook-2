# AI/validation/schemas.py
from pydantic import BaseModel
from typing import Literal

class ValidationInput(BaseModel):
    journal_entry_id: int
    content: str

class Evidence(BaseModel):
    start: int
    end: int

class ValidationOutput(BaseModel):
    journal_entry_id: int
    verdict: Literal["PASS", "REVIEW", "BLOCK"]
    issue_types: list[str] = []
    evidence: list[Evidence] = []