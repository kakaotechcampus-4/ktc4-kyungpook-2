# AI/summary/graph.py
"""
Summary Agent 그래프 조립.

    gather → write → ground → assemble

matching/graph.py · validation/graph.py 와 같은 모양이다. 분기가 없는 것은
요약이 판정이 아니기 때문이다 — 고를 것이 없으니 갈림길도 없다.
"""

from langgraph.graph import END, StateGraph

from .llm import LlmUnavailable, NoGroundedClaims
from .nodes import assemble, gather, ground, write
from .schemas import Claim, Evidence, EvidenceSpan, SummaryInput, SummaryOutput
from .state import SummaryState


def build_graph():
    graph = StateGraph(SummaryState)
    graph.add_node("gather", gather)
    graph.add_node("write", write)
    graph.add_node("ground", ground)
    graph.add_node("assemble", assemble)

    graph.set_entry_point("gather")
    graph.add_edge("gather", "write")
    graph.add_edge("write", "ground")
    graph.add_edge("ground", "assemble")
    graph.add_edge("assemble", END)

    return graph.compile()


#: 요청마다 컴파일하지 않도록 서버 시작 시 한 번만 만든다.
GRAPH = build_graph()


def _to_claim(raw: dict) -> Claim:
    return Claim(
        text=raw["text"],
        evidence=[
            Evidence(
                journal_entry_id=e["journal_entry_id"],
                quote=e["quote"],
                span=EvidenceSpan(**e["span"]),
            )
            for e in raw["evidence"]
        ],
    )


def run_summary(payload: SummaryInput) -> SummaryOutput:
    """
    /summary 엔드포인트의 진입점.

    하는 일은 둘뿐이다 — 판정 로직(nodes.py)은 안 건드린다.
    ① pydantic 모델을 그래프가 쓰는 dict 로 푼다.
    ② 그래프 결과를 다시 pydantic 모델로 포장한다.

    모델 호출이 실패하면 빈 요약을 돌려주지 않고 503 으로 올린다. 빈 글을
    200 으로 주면 "그날 쓸 말이 없었다" 와 "모델이 죽었다" 가 구별되지 않고,
    교사가 빈 요약을 승인하면서 일지는 반영된 것처럼 닫힌다.
    """
    state = {
        "child_id": payload.child_id,
        "child_name": payload.child_name,
        "entry_date": payload.entry_date,
        "institution_id": payload.institution_id,
        "institution_name": payload.institution_name,
        "sources": payload.sources,
        "other_child_names": payload.other_child_names,
    }
    result = GRAPH.invoke(state)

    if result.get("llm_error"):
        raise LlmUnavailable(result["llm_error"])

    # 남은 문장이 0 개인 경우는 둘이다 (CRITERIA.md §2).
    #   모델이 처음부터 빈 claims 를 냈다  → 쓸 관찰 사실이 없다 → 200 no_content
    #   문장은 냈는데 ground 에서 다 버려졌다 → 다시 부르면 될 수 있다 → 503
    # 앞의 경우 content "" · covered [] · uncovered 전부는 assemble 이 이미 그렇게 만든다.
    no_content = bool(payload.sources) and not result.get("raw_claims")
    if payload.sources and not result["claims"] and not no_content:
        raise NoGroundedClaims(
            f"child_id={payload.child_id} {payload.entry_date} "
            f"sources={len(payload.sources)} 중 근거가 남은 문장 0"
        )

    return SummaryOutput(
        child_id=payload.child_id,
        entry_date=payload.entry_date,
        institution_id=payload.institution_id,
        content=result["content"],
        claims=[_to_claim(c) for c in result["claims"]],
        covered_entry_ids=result["covered_entry_ids"],
        uncovered_entry_ids=result["uncovered_entry_ids"],
        needs_review=result.get("needs_review", False),
        review_reasons=result.get("review_reasons", []),
        llm_called=result.get("llm_called", False),
        no_content=no_content,
    )
