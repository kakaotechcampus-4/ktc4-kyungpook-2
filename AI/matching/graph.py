from typing import Literal

from langgraph.graph import END, START, StateGraph

from common.progress import tracked

from .config import EXACT_SCORE
from .nodes import decide, extract, llm_judge, shortlist
from .schemas import Candidate, EvidenceSpan, MatchingInput, MatchingOutput
from .state import MatchingState


def route_after_shortlist(state: MatchingState) -> Literal["llm_judge", "decide"]:
    candidates = state.get("candidates", [])

    if (
        len(candidates) == 1
        and candidates[0]["confidence"] >= EXACT_SCORE
        and not state.get("co_mention")
    ):
        return "decide"

    return "llm_judge"


def build_graph():
    builder = StateGraph(MatchingState)

    builder.add_node("extract", tracked("extract", extract))
    builder.add_node("shortlist", tracked("shortlist", shortlist))
    builder.add_node("llm_judge", tracked("llm_judge", llm_judge))
    builder.add_node("decide", tracked("decide", decide))

    builder.add_edge(START, "extract")
    builder.add_edge("extract", "shortlist")
    builder.add_conditional_edges(
        "shortlist",
        route_after_shortlist,
        {"llm_judge": "llm_judge", "decide": "decide"},
    )
    builder.add_edge("llm_judge", "decide")
    builder.add_edge("decide", END)

    return builder.compile()


GRAPH = build_graph()


def run_matching(payload: MatchingInput) -> MatchingOutput:
    final: MatchingState = GRAPH.invoke(
        {
            "journal_entry_id": payload.journal_entry_id,
            "content": payload.content,
            "roster": payload.roster,
            "hint_name": payload.hint_name,
            "hint_birthdate": payload.hint_birthdate,
        }
    )

    status = final["status"]
    candidates = final.get("candidates", [])

    return MatchingOutput(
        journal_entry_id=payload.journal_entry_id,
        status=status,
        matched_child_id=final.get("matched_child_id"),
        confidence=final.get("confidence", 0.0),
        hint_mismatch=final.get("hint_mismatch", False),
        evidence=[EvidenceSpan(**e) for e in final.get("evidence", [])],
        mentioned_child_ids=final.get("mentioned_child_ids", []),
        multi_reason=final.get("multi_reason"),
        candidates=(
            [Candidate(**c) for c in candidates] if status == "multi" else []
        ),
        llm_called=final.get("llm_called", False),
    )
