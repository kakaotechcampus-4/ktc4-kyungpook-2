# AI/summary/graph.py
"""
Summary Agent 그래프 조립.

    gather → write → ground → assemble

matching/graph.py · validation/graph.py 와 같은 모양이다. 분기가 없는 것은
요약이 판정이 아니기 때문이다 — 고를 것이 없으니 갈림길도 없다.
"""

from langgraph.graph import END, StateGraph

from .llm import LlmUnavailable
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
                span=EvidenceSpan(**e["span"]) if e.get("span") else None,
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
        "sources": payload.sources,
    }
    result = GRAPH.invoke(state)

    if result.get("llm_error"):
        raise LlmUnavailable(result["llm_error"])

    return SummaryOutput(
        child_id=payload.child_id,
        entry_date=payload.entry_date,
        content=result["content"],
        claims=[_to_claim(c) for c in result["claims"]],
        covered_entry_ids=result["covered_entry_ids"],
        uncovered_entry_ids=result["uncovered_entry_ids"],
        llm_called=result.get("llm_called", False),
    )
