from pydantic import BaseModel, Field, model_validator


class EvidenceSpan(BaseModel):

    start: int
    end: int


class SourceEntry(BaseModel):

    journal_entry_id: int
    content: str
    entry_date: str | None = None


class SummaryInput(BaseModel):
    child_id: int
    child_name: str

    entry_date: str

    institution_id: int
    institution_name: str | None = None

    sources: list[SourceEntry]

    other_child_names: list[str] = Field(default_factory=list)


class Evidence(BaseModel):

    journal_entry_id: int

    quote: str

    span: EvidenceSpan


class Claim(BaseModel):

    text: str
    evidence: list[Evidence] = Field(default_factory=list)


class SummaryOutput(BaseModel):
    child_id: int
    entry_date: str
    institution_id: int

    content: str

    claims: list[Claim] = Field(default_factory=list)

    covered_entry_ids: list[int] = Field(default_factory=list)

    uncovered_entry_ids: list[int] = Field(default_factory=list)

    needs_review: bool = False

    review_reasons: list[str] = Field(default_factory=list)

    llm_called: bool = False

    no_content: bool = False

    @model_validator(mode="after")
    def _no_content_is_empty(self):
        if self.no_content and (self.content or self.claims or self.covered_entry_ids):
            raise ValueError("no_content=True 면 content·claims·covered_entry_ids 가 비어야 한다")
        return self
