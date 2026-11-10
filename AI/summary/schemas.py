from pydantic import BaseModel, Field, model_validator


class EvidenceSpan(BaseModel):
    """
    근거가 된 원문 구간. matching/validation 과 같은 모양이다.

    start / end 는 Python 문자열 인덱스(유니코드 코드포인트) 기준이다.
    프론트에서 하이라이트할 때는 String.slice 가 아니라
    Array.from(content).slice(start, end).join('') 로 복원해야 한다.
    """

    start: int
    end: int


class SourceEntry(BaseModel):
    """요약 재료가 되는 일지 한 건. 전부 같은 기관 것이다."""

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
    """
    요약 문장 하나를 뒷받침하는 근거 하나.

    한 문장이 여러 일지에 걸칠 수 있어 Claim 당 여러 개다. "학교에서는 혼자
    했는데 센터에서는 도움이 필요했다" 가 그런 문장이고, 합치는 요약에서
    환각이 가장 잘 생기는 곳도 두 기록을 잇는 바로 그 자리다. 근거를 하나만
    달게 하면 연결 부분이 검사에서 통째로 빠진다.
    """

    journal_entry_id: int

    quote: str

    span: EvidenceSpan


class Claim(BaseModel):
    """
    요약 문장 하나와 그 근거들.

    근거가 하나도 안 남은 문장은 버린다. 그래서 저장 시점의 evidence 는
    비어 있을 수 없다.
    """

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
