# AI/insight/graph.py
"""
Insight Agent 그래프 조립.
인식 -> 계획 -> 행동(Luna 호출) -> 반영 순서로 고정.
"""
from langgraph.graph import END, StateGraph

from .llm import LlmUnavailable
from .nodes import act, perceive, plan, reflect
from .schemas import InsightInput, InsightOutput


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


# 요청마다 새로 컴파일하지 않도록 한 번만 만든다. matching · validation 과 같은 패턴.
GRAPH = build_graph()


def run_insight(payload: InsightInput) -> InsightOutput:
    """
    /insight 엔드포인트의 진입점.

    ① pydantic 입력을 그래프가 쓰는 dict 로 풀고
    ② 그래프 결과를 InsightOutput 으로 포장한다. 판정 로직은 nodes.py 에 있다.

    모델 호출이 실패하면 빈 배열이 아니라 LlmUnavailable 을 던진다.
    빈 배열은 "패턴 없음" 이라는 정답이라, 실패와 섞이면 안 된다.
    """
    print(f"[insight] child_id={payload.child_id} claims={len(payload.claims)} 처리 중")
    result = GRAPH.invoke(payload.model_dump())

    if result.get("llm_error"):
        raise LlmUnavailable("Luna 호출 실패")

    return InsightOutput(
        child_id=payload.child_id,
        period_from=payload.period_from,
        period_to=payload.period_to,
        insights=result["insights"],
        llm_called=result["llm_called"],
    )