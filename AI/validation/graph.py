"""
Validation Agent 그래프 조립.
인식 -> 계획 -> 행동(Luna 호출) -> 반영 순서로 고정.
"""
from langgraph.graph import StateGraph, END
from validation.nodes import perceive, plan, act, reflect
from .schemas import ValidationInput, ValidationOutput, Evidence


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

# 요청마다 그래프를 새로 컴파일하지 않도록, 서버 시작 시 한 번만 만들어서 재사용한다.
# matching/graph.py의 GRAPH 패턴과 동일.
GRAPH = build_graph()

def run_validation(payload: ValidationInput) -> ValidationOutput:
    """
    /validation 엔드포인트의 실제 진입점.

    역할은 딱 두 가지뿐이다 — 판정 로직(nodes.py) 자체는 안 건드린다.
    ① Pydantic 모델(payload)을 그래프가 쓰는 dict(state)로 풀어준다.
    ② 그래프가 낸 결과를 다시 Pydantic 모델(ValidationOutput)로 포장한다.
    """
    state = {
        "journal_entry_id": payload.journal_entry_id,
        "content": payload.content,
        "subject_child_id": payload.subject_child_id,
        "subject_name": payload.subject_name,
    }
    result = GRAPH.invoke(state)

    # journal_entry_id는 판정에 안 쓰이는 값이라 state에 안 넣었다.
    # 응답을 포장할 때 입력받은 값을 그대로 돌려주기만 하면 된다.
    return ValidationOutput(
        journal_entry_id=payload.journal_entry_id,
        verdict=result["verdict"],
        issue_types=result["issue_types"],
        evidence=[Evidence(**e) for e in result["evidence"]],
    )
