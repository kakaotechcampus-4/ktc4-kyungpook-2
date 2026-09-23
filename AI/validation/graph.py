# AI/validation/graph.py
from langgraph.graph import StateGraph, END
from validation.nodes import perceive, plan, act, reflect


def build_graph():
    graph = StateGraph(dict)
    graph.add_node("perceive", perceive)
    graph.add_node("plan", plan)
    graph.add_node("act", act)
    graph.add_node("reflect", reflect)

    graph.set_entry_point("perceive")
    graph.add_edge("perceive", "plan")
    graph.add_edge("plan", "act")
    graph.add_edge("act", "reflect")
    graph.add_edge("reflect", END)

    return graph.compile()