from langgraph.graph import END, StateGraph

from common.progress import tracked

from .llm import LlmUnavailable, NoGroundedClaims
from .nodes import assemble, gather, ground, write
from .schemas import Claim, Evidence, EvidenceSpan, SummaryInput, SummaryOutput
from .state import SummaryState


def build_graph():
    graph = StateGraph(SummaryState)
    graph.add_node("gather", tracked("gather", gather))
    graph.add_node("write", tracked("write", write))
    graph.add_node("ground", tracked("ground", ground))
    graph.add_node("assemble", tracked("assemble", assemble))

    graph.set_entry_point("gather")
    graph.add_edge("gather", "write")
    graph.add_edge("write", "ground")
    graph.add_edge("ground", "assemble")
    graph.add_edge("assemble", END)

    return graph.compile()


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


def run_summary(payload: SummaryInput, trace: dict | None = None) -> SummaryOutput:
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

    if trace is not None:
        trace.update(
            raw_claims=len(result.get("raw_claims") or []),
            dropped_evidence=result.get("dropped_evidence", 0),
            dropped_detail=result.get("dropped_detail", []),
        )

    if result.get("llm_error"):
        raise LlmUnavailable(result["llm_error"], failure=result.get("llm_failure", "unknown"))

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
