# AI/summary/schemas.py
"""
Summary Agent 의 입출력 계약.

matching/schemas.py · validation/schemas.py 와 같은 원칙이다 — 스펙을 문서로만
두면 어긋나도 모르지만, 여기 pydantic 모델로 두면 어긋나는 순간 터진다.

앞의 두 에이전트와 다른 점이 둘 있다.

1. **입력이 기록 한 건이 아니라 N 건이다.** 같은 아이의 같은 날 기록을 기관을
   가로질러 묶는다. 학교에서는 혼자 했는데 센터에서는 도움이 필요했다는 차이는
   묶어야만 보인다. 기관별로 쪼개면 그 문장이 아예 생길 수 없다.

2. **판정이 아니라 생성이다.** 매칭·검증은 "무엇이다" 를 고르지만 요약은 글을
   쓴다. 그래서 재야 할 것이 다르다 — 지어냈는가(환각)와 빠뜨렸는가(누락)다.
   claims 와 uncovered_entry_ids 가 각각을 드러낸다.
"""

from pydantic import BaseModel, Field


class EvidenceSpan(BaseModel):
    """
    근거가 된 원문 구간. matching/validation 과 같은 모양이다.

    start / end 는 Python 문자열 인덱스(유니코드 코드포인트) 기준이다.
    프론트에서 하이라이트할 때는 String.slice 가 아니라
    Array.from(content).slice(start, end).join('') 로 복원해야 한다.
    """

    start: int
    end: int


# ── 입력 ────────────────────────────────────────────────────────


class SourceEntry(BaseModel):
    """요약 재료가 되는 일지 한 건."""

    journal_entry_id: int
    content: str

    #: 어느 기관에서 쓴 기록인지. 요약 본문이 출처를 밝히는 데 쓴다.
    institution_name: str | None = None
    entry_date: str | None = None


class SummaryInput(BaseModel):
    child_id: int
    child_name: str

    #: 묶음 기준 날짜. (child_id, entry_date) 하나당 요약 하나다.
    entry_date: str

    #: 같은 아이·같은 날짜의 일지들. 여러 기관 것이 섞여 있는 것이 정상이다.
    #:
    #: ⚠️ 검증에서 BLOCK 판정된 기록은 여기 넣지 않는다. 개인정보가 든 기록이
    #: 요약으로 들어가면 Gate 1 이전에 이미 유출이다. 거르는 쪽은 백엔드다.
    sources: list[SourceEntry]


# ── 출력 ────────────────────────────────────────────────────────


class Claim(BaseModel):
    """
    요약 문장 하나와 그 근거.

    모델이 지어낸 문장은 span 이 원문과 맞지 않아 코드가 버린다. 매칭·검증이
    인용을 원문에 대조하는 것과 같은 방법이다 (spans_for_quotes).
    """

    text: str
    journal_entry_id: int

    #: 그 사실이 어느 기관에서 나왔는지. SourceEntry 의 값을 그대로 되돌려준다.
    institution_name: str | None = None
    span: EvidenceSpan


class SummaryOutput(BaseModel):
    child_id: int
    entry_date: str

    #: 요약 본문. **부모가 아니라 교사와 다른 기관 담당자가 읽는다.**
    #: Gate 1 승인 뒤 child_context 로 쌓이고, 부모가 받는 것은 그다음
    #: 단계인 인사이트다.
    content: str

    #: 본문의 문장별 근거. 원문에서 못 찾은 문장은 들어가지 않는다.
    claims: list[Claim] = Field(default_factory=list)

    #: 요약에 실제로 반영된 일지.
    covered_entry_ids: list[int] = Field(default_factory=list)

    #: 반영되지 않은 일지. **비어 있는 것이 정상이다.**
    #: 비어 있지 않다고 늘 잘못은 아니다 — "특이사항 없음" 같은 기록은 뺄 수
    #: 있다. 다만 뺐다는 사실이 드러나야 사람이 판단할 수 있다.
    uncovered_entry_ids: list[int] = Field(default_factory=list)

    #: LLM 을 실제로 호출했는지. 호출률 측정용.
    llm_called: bool = False
