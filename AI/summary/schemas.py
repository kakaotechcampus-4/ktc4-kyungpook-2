# AI/summary/schemas.py
"""
Summary Agent 의 입출력 계약.

matching/schemas.py · validation/schemas.py 와 같은 원칙이다 — 스펙을 문서로만
두면 어긋나도 모르지만, 여기 pydantic 모델로 두면 어긋나는 순간 터진다.

앞의 두 에이전트와 다른 점이 둘 있다.

1. **입력이 기록 한 건이 아니라 N 건이다.** 같은 아이의 같은 날 기록을 묶는다.
   다만 **한 기관 안에서만** 묶는다 — 기관을 가로지르면 Gate 1 에서 누가
   승인하는지가 사라지고, 승인 전에 이미 섞인다. 기관을 가로지르는 비교는
   `child_context` 를 읽는 인사이트가 한다 (schema.md §9.2).

2. **판정이 아니라 생성이다.** 매칭·검증은 "무엇이다" 를 고르지만 요약은 글을
   쓴다. 그래서 재야 할 것이 다르다 — 지어냈는가(환각)와 빠뜨렸는가(누락)다.
   claims 와 uncovered_entry_ids 가 각각을 드러낸다.

무엇을 쓰고 무엇을 버리는지는 CRITERIA.md 에 있다.
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
    """요약 재료가 되는 일지 한 건. 전부 같은 기관 것이다."""

    journal_entry_id: int
    content: str
    entry_date: str | None = None


class SummaryInput(BaseModel):
    child_id: int
    child_name: str

    #: 묶음 기준 날짜. (child_id, entry_date, institution_id) 하나당 요약 하나다.
    entry_date: str

    #: 이 요약을 쓰는 기관. **묶음 키의 일부다.**
    #: 보호자 동의가 기관별(sharing_consent)이고 Gate 1 승인 주체도 그 기관의
    #: 작성자라, 기관을 가로질러 묶으면 승인할 사람이 없어진다.
    institution_id: int
    institution_name: str | None = None

    #: 같은 아이·같은 날짜·**같은 기관**의 일지들.
    #:
    #: ⚠️ 검증에서 BLOCK 판정된 기록은 여기 넣지 않는다. 개인정보가 든 기록이
    #: 요약으로 들어가면 Gate 1 이전에 이미 유출이다. 거르는 쪽은 백엔드다 —
    #: #100 의 워커가 VALIDATED 만 가져간다.
    sources: list[SourceEntry]

    #: 이 묶음의 일지 본문에 이름이 나온 **다른** 아이들. BE 가 매칭의
    #: mentioned_child_ids 를 명부와 조인해 이름으로 바꿔 보낸다 (#146).
    #:
    #: ⚠️ **모델에게 보여주지 않는다.** 프롬프트에 넣으면 그 이름이 로그와
    #: raw_response 에 남는다. 출력 대조에만 쓴다.
    #:
    #: ⚠️ ACTIVE 명부 아이만 담긴다. 동의 전 아동·다른 기관 아이·형제·교사·
    #: 성을 뗀 이름·오타는 빠진다 — **동의하지 않은 사람일수록 빠진다.**
    #: 그래서 차단이 아니라 측정·표시로만 쓴다 (CRITERIA §3).
    other_child_names: list[str] = Field(default_factory=list)


# ── 출력 ────────────────────────────────────────────────────────


class Evidence(BaseModel):
    """
    요약 문장 하나를 뒷받침하는 근거 하나.

    한 문장이 여러 일지에 걸칠 수 있어 Claim 당 여러 개다. "학교에서는 혼자
    했는데 센터에서는 도움이 필요했다" 가 그런 문장이고, 합치는 요약에서
    환각이 가장 잘 생기는 곳도 두 기록을 잇는 바로 그 자리다. 근거를 하나만
    달게 하면 연결 부분이 검사에서 통째로 빠진다.
    """

    journal_entry_id: int

    #: 모델이 원문에서 그대로 따온 구절. 다듬으면 코드가 못 찾아 버려진다.
    quote: str

    #: quote 의 위치. **모델이 주지 않는다** — 코드가 원문에서 찾아 채운다.
    #: 모델은 글자 수를 세지 못해 좌표를 거의 틀린다 (matching/llm.py:126).
    #:
    #: 이 계약은 **저장되는 모양**이라 필수다. 모델 응답을 받을 때는 비어 있지만
    #: 그 상태는 여기까지 오지 않는다 — 원문에서 못 찾은 근거는 ground 가 버린다.
    #: 예전에는 Optional 이었는데, BE 의 근거 재검사가 span 을 필수로 보고 있어
    #: 없으면 요약 전체가 실패한다 (#146). 느슨한 쪽이 제 계약이었다.
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

    #: 요약 본문. **claims[].text 를 배열 순서대로 이은 것이다.**
    #: 모델에게 따로 받지 않는다 — 따로 받으면 claims 밖 문장이 생겨
    #: 환각 검사가 본문을 다 덮지 못한다.
    #:
    #: 읽는 사람은 **부모가 아니라 교사와 다른 기관 담당자다.** Gate 1 승인 뒤
    #: child_context 로 쌓이고, 부모가 받는 것은 그다음 단계인 인사이트다.
    content: str

    #: 본문의 문장별 근거. 원문에서 못 찾은 인용은 코드가 버리고,
    #: 근거가 0 개가 된 문장은 본문에서도 빠진다.
    claims: list[Claim] = Field(default_factory=list)

    #: 요약에 실제로 반영된 일지. **코드가 센다.**
    #: 살아남은 근거가 가리키는 일지를 모은 것이고, 모델에게 묻지 않는다 —
    #: 모델 보고는 측정이 아니라 자기채점이다.
    covered_entry_ids: list[int] = Field(default_factory=list)

    #: 반영되지 않은 일지. **비어 있는 것이 정상이다.**
    #: 비어 있지 않다고 늘 잘못은 아니다 — "특이사항 없음" 같은 기록은 뺄 수
    #: 있다. 다만 뺐다는 사실이 드러나야 사람이 판단할 수 있다.
    uncovered_entry_ids: list[int] = Field(default_factory=list)

    #: 공유 전에 사람이 봐야 하는가. 교사가 Gate 1 에서 처리한다.
    #: **막지는 않는다** — 경고로 띄우고 고칠지는 교사가 정한다
    #: (2026-10-08 멘토 리뷰 P1).
    needs_review: bool = False

    #: 왜 봐야 하는지. 검증의 issue_types 와 같은 모양이다.
    #: 지금은 "다른아동이름" 하나뿐이다.
    review_reasons: list[str] = Field(default_factory=list)

    #: LLM 을 실제로 호출했는지. 호출률 측정용.
    llm_called: bool = False
