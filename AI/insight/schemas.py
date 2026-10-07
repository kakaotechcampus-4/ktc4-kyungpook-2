# AI/insight/schemas.py
"""
Insight Agent 의 입출력 계약.

matching · validation · summary 와 같은 원칙이다 — 스펙을 pydantic 모델로 두면
어긋나는 순간 터진다.

앞의 에이전트들과 다른 점:

1. **입력이 일지가 아니라 승인된 요약의 claims 다.** 여러 날·여러 기관의
   claims 를 모아 "상황 → 반응 → 대처와 결과" 묶음을 찾는다.
2. **모델은 근거 번호(claim_id)만 가리킨다.** 출처(기관·날짜·일지)와 기관 간 비교
   여부는 코드가 계산한다.
3. **수신 기관은 정하지 않는다.** 근거를 낸 기관만 내고, 누구에게 보일지는 BE 가
   Gate 2 시점의 동의로 정한다. 동의는 인사이트를 만든 뒤에도 바뀌기 때문이다.

무엇이 인사이트로 성립하고 무엇을 쓰지 않는지는 CRITERIA.md 에 있다.
"""

from pydantic import BaseModel, Field


# ── 입력 ────────────────────────────────────────────────────────


class InsightEvidence(BaseModel):
    """claim 하나를 뒷받침하는 원본 일지 근거. summary.schemas.Evidence 와 같은 모양이다."""

    journal_entry_id: int

    #: ⚠️ 원문 인용이라 다른 아이 이름이 남아 있다. claim 의 text 는 익명화됐지만 이건 아니다.
    #: 모델 프롬프트에 넣지 않고, 출력으로도 옮기지 않는다 (CRITERIA §2).
    quote: str


class InsightClaim(BaseModel):
    """
    승인된 요약 문장 하나.

    claim_id 는 요약에 없는 값이라 BE 가 붙인다: "{summary_id}-{claims 배열 순서}".
    요약이 배열 순서를 본문 순서로 못 박았으므로 같은 판 안에서는 바뀌지 않는다.
    """

    claim_id: str
    text: str
    entry_date: str

    #: 기관 간 비교와 기관 종류 대조에 쓴다. 이름은 받지 않는다.
    institution_id: int
    institution_type: str  # 학교 / 센터 / 학원

    #: ⚠️ 최소 1개. Gate 1 에서 근거가 끊긴 "교사 작성" 문장은 넣지 않는다.
    #: 원본으로 추적되지 않는 내용이 다른 기관으로 나가면 안 된다 (CRITERIA §2).
    evidence: list[InsightEvidence] = Field(min_length=1)


class Institution(BaseModel):
    institution_id: int
    institution_type: str


class InsightInput(BaseModel):
    child_id: int
    child_name: str
    period_from: str
    period_to: str

    claims: list[InsightClaim]

    #: 보호자의 기관별 공유 동의(sharing_consent)가 ACTIVE 인 기관.
    #: 이 밖의 기관 claim 은 재료에서 뺀다 (CRITERIA §7). 모델에게 보여주지 않는다.
    #: sharing_consent 가 구현되기 전까지 BE 는 아이가 등록된 기관 전부를 넣는다.
    consented_institutions: list[Institution] = Field(default_factory=list)


# ── 출력 ────────────────────────────────────────────────────────


class Support(BaseModel):
    """기록된 대처 하나와 그 결과. 결과가 기록에 없는 대처는 코드가 버린다."""

    action: str
    result: str
    claim_ids: list[str] = Field(min_length=1)


class Insight(BaseModel):
    # ── 모델이 쓰는 것 ──
    situation: str
    behavior: str
    supports: list[Support] = Field(default_factory=list)
    claim_ids: list[str]
    content: str

    # ── 코드가 채우는 것 (모델 응답을 받을 때는 비어 있다) ──
    source_entry_ids: list[int] = Field(default_factory=list)
    #: 근거를 낸 기관. 수신 후보는 BE 가 Gate 2 시점에 이 값과 그때의 동의로 계산한다.
    source_institution_ids: list[int] = Field(default_factory=list)
    institution_types: list[str] = Field(default_factory=list)
    cross_institution: bool = False


class InsightOutput(BaseModel):
    child_id: int
    period_from: str
    period_to: str

    #: 비어 있는 것도 정답이다. 억지로 만든 패턴이 과잉해석이다 (CRITERIA §3).
    insights: list[Insight] = Field(default_factory=list)

    llm_called: bool = False