"""
Matching Agent 의 입출력 계약.

이 파일이 트랙 A(채점 스크립트)와 공유하는 유일한 기준이다.
스펙을 문서로만 두면 어긋나도 모르지만, 여기 모델로 두면 어긋나는 순간 터진다.
예를 들어 status 에 "confirmed" 를 넣으면 ValidationError 가 난다.
"""

from typing import Literal

from pydantic import BaseModel, Field

#: 사람이 무엇을 해야 하는가로 이름을 지었다.
#: auto = 할 일 없음 / review = 맞는지 확인 / multi = 고르기 / unmatched = 검색
MatchStatus = Literal["auto", "review", "multi", "unmatched"]

#: multi 인 이유. 화면이 이 값으로 문구를 다르게 보여준다.
#: ambiguous_identity → "누구 것인지 골라주세요"
#: co_mention         → "두 아이가 함께 나옵니다. 누구 기록으로 저장할까요?"
MultiReason = Literal["ambiguous_identity", "co_mention"]


# ── 입력 ────────────────────────────────────────────────────────


class RosterEntry(BaseModel):
    """매칭 후보가 될 수 있는 등록 아동 한 명."""

    child_id: int
    name: str
    birthdate: str


class MatchingInput(BaseModel):
    journal_entry_id: int
    content: str
    roster: list[RosterEntry]

    raw_record_id: int | None = None
    entry_date: str | None = None

    #: 표지 힌트. 정답이 아니라 1차 단서다. 표지가 없는 일지도 있어서 없을 수 있다.
    hint_name: str | None = None
    hint_birthdate: str | None = None


# ── 출력 ────────────────────────────────────────────────────────


class EvidenceSpan(BaseModel):
    """
    판정 근거가 된 본문 구간.

    start / end 는 Python str 인덱스(유니코드 코드포인트) 기준이다.
    프론트에서 하이라이트할 때는 String.slice 가 아니라
    Array.from(content).slice(start, end).join('') 로 복원해야 한다.
    (이모지가 섞이면 JavaScript 의 인덱스가 밀린다)
    """

    start: int
    end: int


class Candidate(BaseModel):
    child_id: int
    confidence: float


class MatchingOutput(BaseModel):
    journal_entry_id: int
    status: MatchStatus

    #: multi / unmatched 일 때는 항상 None
    matched_child_id: int | None = None

    #: 항상 존재한다. 최고점 후보의 점수 (multi 면 candidates[0].confidence 와 같다)
    confidence: float = 0.0

    #: 표지 힌트와 다른 아이로 판단했는지
    hint_mismatch: bool = False

    #: 판정 근거가 된 최소 구간만. 문단이나 본문 전체를 지정하지 않는다.
    #: LLM 을 호출한 케이스에는 반드시 채운다.
    evidence: list[EvidenceSpan] = Field(default_factory=list)

    #: 본문에 이름이 등장한 아동 전체(후보든 아니든).
    #: Validation 이 "다수 아동 언급" 여부를 독립적으로 재판단하기 위한 참고 정보일 뿐,
    #: 최종 판단이 아니다.
    mentioned_child_ids: list[int] = Field(default_factory=list)

    multi_reason: MultiReason | None = None

    #: multi 일 때만 채운다
    candidates: list[Candidate] = Field(default_factory=list)

    #: LLM 을 실제로 호출했는지. 호출률 측정용.
    llm_called: bool = False
