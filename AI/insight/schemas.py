from typing import Literal

from pydantic import BaseModel, Field, model_validator


class InsightEvidence(BaseModel):
    """claim 하나를 뒷받침하는 원본 일지 근거. summary.schemas.Evidence 와 같은 모양이다."""

    journal_entry_id: int | None = None

    quote: str

ClaimSource = Literal["AI", "EDITED", "TEACHER"]

class InsightClaim(BaseModel):
    """
    승인된 요약 문장 하나.

    claim_id 는 요약에 없는 값이라 BE 가 붙인다: "{summary_id}-{claims 배열 순서}".
    요약이 배열 순서를 본문 순서로 못 박았으므로 같은 판 안에서는 바뀌지 않는다.
    """

    claim_id: str
    text: str
    entry_date: str

    institution_id: int
    institution_type: str

    source: ClaimSource = "AI"

    evidence: list[InsightEvidence] = Field(min_length=1)

    @model_validator(mode="after")
    def _only_teacher_without_entry(self):
        if self.source != "TEACHER" and any(e.journal_entry_id is None for e in self.evidence):
            raise ValueError("journal_entry_id 는 source=TEACHER 문장에서만 비울 수 있다")
        return self


class Institution(BaseModel):
    institution_id: int
    institution_type: str


class InsightInput(BaseModel):
    child_id: int
    child_name: str
    period_from: str
    period_to: str

    claims: list[InsightClaim]

    consented_institutions: list[Institution] = Field(default_factory=list)

    history_claims: list[InsightClaim] = Field(default_factory=list)


class Support(BaseModel):
    """기록된 대처 하나와 그 결과. 결과가 기록에 없는 대처는 코드가 버린다."""

    action: str
    result: str
    claim_ids: list[str] = Field(min_length=1)


class Insight(BaseModel):
    situation: str
    behavior: str
    supports: list[Support] = Field(default_factory=list)
    claim_ids: list[str]
    content: str

    source_entry_ids: list[int] = Field(default_factory=list)
    source_institution_ids: list[int] = Field(default_factory=list)
    institution_types: list[str] = Field(default_factory=list)
    cross_institution: bool = False
    teacher_claim_ids: list[str] = Field(default_factory=list)
    looked_up_claim_ids: list[str] = Field(default_factory=list)


class InsightOutput(BaseModel):
    child_id: int
    period_from: str
    period_to: str

    insights: list[Insight] = Field(default_factory=list)

    llm_called: bool = False