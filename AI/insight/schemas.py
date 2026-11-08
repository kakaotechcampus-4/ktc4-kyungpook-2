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

from typing import Literal

from pydantic import BaseModel, Field, model_validator


# ── 입력 ────────────────────────────────────────────────────────


class InsightEvidence(BaseModel):
    """claim 하나를 뒷받침하는 원본 일지 근거. summary.schemas.Evidence 와 같은 모양이다."""

    #: 교사가 Gate 1 에서 새로 쓴 문장(source=TEACHER)은 일지가 없어 비어 있다.
    #: 그때 quote 는 그 문장 자체다 — 교사 기록이 곧 원문이다.
    journal_entry_id: int | None = None

    #: ⚠️ 원문 인용이라 다른 아이 이름이 남아 있다. claim 의 text 는 익명화됐지만 이건 아니다.
    #: 모델 프롬프트에 넣지 않고, 출력으로도 옮기지 않는다 (CRITERIA §2).
    quote: str

#: AI = 요약 AI 원문 그대로, EDITED = 교사가 고친 문장(근거는 원래 일지),
#: TEACHER = 교사가 새로 쓴 문장(근거는 자기 자신). Gate 1 카드 결과로 BE 가 붙인다.
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

    #: 기관 간 비교와 기관 종류 대조에 쓴다. 이름은 받지 않는다.
    institution_id: int
    institution_type: str  # 학교 / 센터 / 활동지원사

    #: 없으면 AI. 교사 문장은 검증을 거치지 않으므로 출력에 표시한다 (#199).
    source: ClaimSource = "AI"

    #: ⚠️ 최소 1개. 원본으로 추적되지 않는 내용이 다른 기관으로 나가면 안 된다 (CRITERIA §2).
    evidence: list[InsightEvidence] = Field(min_length=1)

    @model_validator(mode="after")
    def _only_teacher_without_entry(self):
        # 일지 번호를 비울 수 있는 건 교사가 새로 쓴 문장뿐이다.
        # AI·EDITED 문장에서 번호가 빠졌다면 근거가 끊긴 것이므로 422 로 돌려보낸다.
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

    #: 보호자의 기관별 공유 동의(sharing_consent)가 ACTIVE 인 기관.
    #: 이 밖의 기관 claim 은 재료에서 뺀다 (CRITERIA §7). 모델에게 보여주지 않는다.
    #: sharing_consent 가 구현되기 전까지 BE 는 아이가 등록된 기관 전부를 넣는다.
    consented_institutions: list[Institution] = Field(default_factory=list)

    #: 같은 아이의 **지난** 승인된 claim (이번 기간보다 앞). 도구 사용(INSIGHT_TOOLS)에서
    #: find_claims 가 이 안에서만 찾는다 — 조회 범위는 모델이 아니라 BE 가 넣은 이 목록이 정한다.
    #: claims 와 같이 동의 밖 기관 것은 perceive 가 뺀다. 비어 있으면 도구를 쓰지 않는다.
    history_claims: list[InsightClaim] = Field(default_factory=list)


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

    #: 보호자가 읽을 같은 내용 (#433). 해요체다.
    #:
    #: **비어 있을 수 있다.** 모델이 안 썼거나, 코드 검사(숫자·기관·말투)에 걸려 버린 경우다.
    #: 그때 보호자 화면은 content 로 떨어진다 — 교사 말투지만 글이 사라지는 것보다 낫다.
    #:
    #: content 와 **같은 근거**에서 나온다. 새 사실을 더하면 버린다 — 교사는 이 문장까지 보고
    #: Gate 2 에서 승인하므로, 승인한 적 없는 말이 섞이면 안 된다.
    guardian_message: str = ""

    # ── 코드가 채우는 것 (모델 응답을 받을 때는 비어 있다) ──
    source_entry_ids: list[int] = Field(default_factory=list)
    #: 근거를 낸 기관 (대처 근거 포함). 동의 철회 시 BE 가 이 값으로 인사이트를 찾는다.
    source_institution_ids: list[int] = Field(default_factory=list)
    institution_types: list[str] = Field(default_factory=list)
    cross_institution: bool = False
    #: 근거(대처 근거 포함) 중 교사가 Gate 1 에서 고치거나 새로 쓴 claim (source=EDITED·TEACHER).
    #: 검증을 거치지 않은 문장이라, 비어 있지 않으면 Gate 2 에서 "교사 작성 포함" 으로 표시한다 (#199).
    #: ⚠️ claim_ids 의 부분집합이 아니다 — 대처(supports) 근거 번호도 들어간다.
    teacher_claim_ids: list[str] = Field(default_factory=list)
    #: 도구(find_claims)로 지난 기록에서 가져와 근거로 쓴 claim. 비어 있지 않으면 Gate 2 에서
    #: "지난 기록을 찾아 씀" 으로 표시한다. claim_ids · supports 근거 안에 들어 있다.
    looked_up_claim_ids: list[str] = Field(default_factory=list)


class InsightOutput(BaseModel):
    child_id: int
    period_from: str
    period_to: str

    #: 비어 있는 것도 정답이다. 억지로 만든 패턴이 과잉해석이다 (CRITERIA §3).
    insights: list[Insight] = Field(default_factory=list)

    llm_called: bool = False