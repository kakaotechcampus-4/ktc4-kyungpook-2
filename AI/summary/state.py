# AI/summary/state.py
"""
그래프가 들고 다니는 값들.

matching/state.py 와 같은 이유로 TypedDict 를 쓴다 — StateGraph(dict) 로 두면
노드가 돌려준 dict 가 상태를 통째로 **대체**해서, 앞 노드가 넣은 값이 조용히
사라진다. 스키마를 주면 돌려준 키만 합쳐진다.

여기 적힌 주석이 "이 값은 누가 채우고 누가 읽는가" 의 유일한 기록이다.
"""

from typing import TypedDict

from .schemas import SourceEntry


class SummaryState(TypedDict, total=False):
    # ── 입력 (그래프가 도는 동안 바뀌지 않는다) ──
    child_id: int
    child_name: str
    entry_date: str
    #: 묶음 키의 일부. 한 기관 안에서만 묶는다 (CRITERIA.md §1).
    institution_id: int
    institution_name: str | None
    sources: list[SourceEntry]
    #: 본문에 남았는지 대조할 다른 아이 이름. 프롬프트에는 넣지 않는다.
    other_child_names: list[str]

    # ── gather 가 채운다 ──
    #: journal_entry_id → 그 일지 본문. 인용을 **그 일지 안에서만** 찾는다.
    by_id: dict[int, str]

    # ── write 가 채운다 ──
    llm_called: bool
    #: 호출이 실패했을 때의 사유. 값이 있으면 run_summary 가 503 으로 올린다.
    llm_error: str | None
    #: 토큰 사용량. cached_tokens 로 프롬프트 캐싱이 먹는지 확인한다.
    llm_usage: dict
    #: 모델이 낸 claims 원본. 아직 아무것도 대조하지 않은 상태다.
    raw_claims: list

    # ── ground 가 채운다 ──
    #: 원문 대조를 통과한 문장들. 이것만 본문이 된다.
    claims: list[dict]
    #: 원문에서 못 찾아 버린 근거 수. 모델 품질 지표다.
    dropped_evidence: int
    #: 근거가 없거나 숫자·기관명이 안 맞아 버린 문장들.
    dropped_claims: list[str]

    # ── assemble 이 채운다 (최종 출력이 되는 값들) ──
    content: str
    covered_entry_ids: list[int]
    uncovered_entry_ids: list[int]
    #: 공유 전에 사람이 봐야 하는가. 막지는 않는다.
    needs_review: bool
    review_reasons: list[str]
