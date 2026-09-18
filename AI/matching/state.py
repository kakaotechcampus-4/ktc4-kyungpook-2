"""
노드 사이를 흐르는 데이터.

각 노드는 이 State 를 받아서 자기가 채울 몫만 돌려준다.
LangGraph 가 돌려받은 부분을 State 에 합쳐서 다음 노드로 넘긴다.
"""

from typing import TypedDict

from .names import NameHit
from .schemas import RosterEntry


class ScoredCandidate(TypedDict):
    child_id: int
    confidence: float


class MatchingState(TypedDict, total=False):
    # ── 입력 (그래프가 도는 동안 바뀌지 않는다) ──
    journal_entry_id: int
    content: str
    roster: list[RosterEntry]
    hint_name: str | None
    hint_birthdate: str | None

    # ── extract 가 채운다 ──
    hits: list[NameHit]

    # ── shortlist 가 채운다 ──
    candidates: list[ScoredCandidate]
    mentioned_child_ids: list[int]
    co_mention: bool
    #: 본문에 이름이 그대로 적힌 아이가 한 명이라도 있었는지.
    #: 없으면 판단 근거가 추론뿐이라 자동 확정하지 않는다.
    has_exact: bool
    #: 표지 힌트의 이름이 명부에 있는지. 없으면 미등록 아동일 수 있다.
    hint_in_roster: bool

    # ── llm_judge 가 채운다 ──
    llm_called: bool
    #: 모델이 인용한 근거 구간. 있으면 decide 가 이름 위치보다 우선해서 쓴다.
    llm_evidence: list[dict]
    #: 호출이 실패했을 때의 사유. 값이 있으면 auto 로 확정하지 않는다.
    llm_error: str | None
    #: 모델이 명부에 없는 child_id 를 돌려줬는지. True 면 auto 로 확정하지 않는다.
    llm_off_roster: bool
    #: 토큰 사용량. cached_tokens 로 프롬프트 캐싱이 먹는지 확인한다.
    llm_usage: dict

    # ── decide 가 채운다 (최종 출력이 되는 값들) ──
    status: str
    matched_child_id: int | None
    confidence: float
    hint_mismatch: bool
    evidence: list[dict]
    multi_reason: str | None
