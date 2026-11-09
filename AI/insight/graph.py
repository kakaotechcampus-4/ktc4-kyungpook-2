from langgraph.graph import END, StateGraph

from common.progress import tracked

from .llm import LlmUnavailable
from .nodes import act, perceive, plan, reflect, refine
from .schemas import InsightInput, InsightOutput


def build_graph():
    graph = StateGraph(dict)
    graph.add_node("perceive", tracked("perceive", perceive))
    graph.add_node("plan", tracked("plan", plan))
    graph.add_node("act", tracked("act", act))
    graph.add_node("reflect", tracked("reflect", reflect))
    graph.add_node("refine", tracked("refine", refine))

    graph.set_entry_point("perceive")
    graph.add_edge("perceive", "plan")
    graph.add_edge("plan", "act")
    graph.add_edge("act", "reflect")
    graph.add_edge("reflect", "refine")
    graph.add_edge("refine", END)
    return graph.compile()


GRAPH = build_graph()


def run_insight(payload: InsightInput, trace: dict | None = None) -> InsightOutput:
    print(f"[insight] child_id={payload.child_id} claims={len(payload.claims)} 처리 중")
    result = GRAPH.invoke(payload.model_dump())

    if trace is not None:
        trace.update(weak_found=result.get("weak_found", []), weak_dropped=result.get("weak_dropped", []),
                     weak_refined=result.get("weak_refined", []), refine_calls=result.get("refine_calls", 0),
                     history_claims=len(result.get("history_index") or {}),
                     tone_violations=result.get("tone_violations", 0))

    if result.get("llm_error"):
        raise LlmUnavailable(result.get("llm_error") or "Luna 호출 실패",
                             failure=result.get("llm_failure", "unknown"))

    return InsightOutput(
        child_id=payload.child_id,
        period_from=payload.period_from,
        period_to=payload.period_to,
        insights=result["insights"],
        llm_called=result["llm_called"],
    )