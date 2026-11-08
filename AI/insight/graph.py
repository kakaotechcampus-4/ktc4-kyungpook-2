# AI/insight/graph.py
"""
Insight Agent 그래프 조립.
인식 -> 계획 -> 행동(Luna 호출) -> 반영 순서로 고정.
"""
from langgraph.graph import END, StateGraph

from .llm import LlmUnavailable
from .nodes import act, perceive, plan, reflect, refine
from .schemas import InsightInput, InsightOutput


def build_graph():
    graph = StateGraph(dict)
    graph.add_node("perceive", perceive)
    graph.add_node("plan", plan)
    graph.add_node("act", act)
    graph.add_node("reflect", reflect)
    graph.add_node("refine", refine)  # INSIGHT_WEAK_MODE="refine" 일 때만 일한다

    graph.set_entry_point("perceive")
    graph.add_edge("perceive", "plan")
    graph.add_edge("plan", "act")
    graph.add_edge("act", "reflect")
    graph.add_edge("reflect", "refine")
    graph.add_edge("refine", END)
    return graph.compile()


# 요청마다 새로 컴파일하지 않도록 한 번만 만든다. matching · validation 과 같은 패턴.
GRAPH = build_graph()


def run_insight(payload: InsightInput, trace: dict | None = None) -> InsightOutput:
    """
    /insight 엔드포인트의 진입점.

    ① pydantic 입력을 그래프가 쓰는 dict 로 풀고
    ② 그래프 결과를 InsightOutput 으로 포장한다. 판정 로직은 nodes.py 에 있다.

    모델 호출이 실패하면 빈 배열이 아니라 LlmUnavailable 을 던진다.
    빈 배열은 "패턴 없음" 이라는 정답이라, 실패와 섞이면 안 된다.
    """
    print(f"[insight] child_id={payload.child_id} claims={len(payload.claims)} 처리 중")
    result = GRAPH.invoke(payload.model_dump())

    # 평가 스크립트만 trace 를 넘긴다 (요약과 같은 방식). 약한 인사이트와 이유를 담아 준다.
    if trace is not None:
        trace.update(weak_found=result.get("weak_found", []), weak_dropped=result.get("weak_dropped", []),
                     weak_refined=result.get("weak_refined", []), refine_calls=result.get("refine_calls", 0),
                     history_claims=len(result.get("history_index") or {}),
                     guardian_missing=result.get("guardian_missing", 0))

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