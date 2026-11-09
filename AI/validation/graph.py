from langgraph.graph import StateGraph, END
from common.progress import tracked
from validation.nodes import perceive, plan, act, reflect
from .llm import LlmUnavailable
from .schemas import ValidationInput, ValidationOutput, Evidence, Issue


def build_graph():
    graph = StateGraph(dict)
    graph.add_node("perceive", tracked("perceive", perceive))
    graph.add_node("plan", tracked("plan", plan))
    graph.add_node("act", tracked("act", act))
    graph.add_node("reflect", tracked("reflect", reflect))

    graph.set_entry_point("perceive")
    graph.add_edge("perceive", "plan")
    graph.add_edge("plan", "act")
    graph.add_edge("act", "reflect")
    graph.add_edge("reflect", END)

    return graph.compile()

GRAPH = build_graph()

def run_validation(payload: ValidationInput) -> ValidationOutput:
    state = {
        "journal_entry_id": payload.journal_entry_id,
        "content": payload.content,
        "subject_child_id": payload.subject_child_id,
        "subject_name": payload.subject_name,
    }
    result = GRAPH.invoke(state)

    if result.get("llm_error") and result["verdict"] != "BLOCK":
        raise LlmUnavailable(result.get("llm_error_detail") or "Luna 호출 실패")

    return ValidationOutput(
        journal_entry_id=payload.journal_entry_id,
        verdict=result["verdict"],
        issue_types=result["issue_types"],
        evidence=[Evidence(**e) for e in result["evidence"]],
        issues=[Issue(**i) for i in result["issues"]],
    )
