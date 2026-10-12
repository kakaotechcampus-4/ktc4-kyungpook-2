"""
Validation Agent 그래프 조립.
인식 -> 계획 -> 행동(Luna 호출) -> 반영 순서로 고정.
"""
from langgraph.graph import StateGraph, END
from validation.nodes import perceive, plan, act, reflect
from .llm import LlmUnavailable
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

    # LLM 이 실패하면 reflect 는 REVIEW["모델호출실패"] 를 낸다. 하지만 검증을 못 한 일지를
    # "확인할 부분이 있는 일지"로 보내면 BE 가 구분할 수 없어서, API 에서는 503 으로 돌려준다.
    # 정규식으로 이미 BLOCK 이 확정된 경우는 모델과 상관없이 막히므로 200 + BLOCK 을 그대로 보낸다.
    # (평가 스크립트는 그래프를 직접 부르므로 이 분기를 거치지 않는다)
    if result.get("llm_error") and result["verdict"] != "BLOCK":
        raise LlmUnavailable(result.get("llm_error_detail") or "Luna 호출 실패")

    # journal_entry_id는 판정에 안 쓰이는 값이라 state에 안 넣었다.
    # 응답을 포장할 때 입력받은 값을 그대로 돌려주기만 하면 된다.
    return ValidationOutput(
        journal_entry_id=payload.journal_entry_id,
        verdict=result["verdict"],
        issue_types=result["issue_types"],
        evidence=[Evidence(**e) for e in result["evidence"]],
    )
