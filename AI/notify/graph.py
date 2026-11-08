# AI/notify/graph.py
"""
Notify Agent 그래프 조립 (#419).

    START → compose → check ─┬─(통과)─→ send → END
                             └─(막힘)──────────→ END

**조건부 엣지가 이 그래프의 핵심이다.** 매칭이 "명확하면 모델을 안 부른다" 를
구조로 드러내듯, 여기서는 "근거를 못 대면 메일이 안 나간다" 가 그래프에 보인다.
보낼지 말지를 모델이 정하지 않는다 — 코드가 정한다.
"""

from typing import Literal

from langgraph.graph import END, START, StateGraph

from .llm import LlmUnavailable, NotifyDeliveryFailed
from .nodes import check, compose, send
from .schemas import BlockedLine, NotifyInput, NotifyOutput
from .state import NotifyState


def route_after_check(state: NotifyState) -> Literal["send", "__end__"]:
    """
    메일을 내보낼지 정한다. 이 한 줄이 아동 정보가 바깥으로 나가는 유일한 관문이다.

    통과 못 하면 보내지 않고 끝낸다. 걸린 줄만 빼고 보내지 않는다 —
    한 줄 빠진 메일은 교사가 승인한 적 없는 조합이다 (nodes.py 머리말).
    """
    return "send" if state.get("passed") else END


def build_graph():
    builder = StateGraph(NotifyState)

    builder.add_node("compose", compose)
    builder.add_node("check", check)
    builder.add_node("send", send)

    builder.add_edge(START, "compose")
    builder.add_edge("compose", "check")
    builder.add_conditional_edges("check", route_after_check, {"send": "send", END: END})
    builder.add_edge("send", END)

    return builder.compile()


#: 그래프는 상태를 갖지 않으므로 한 번만 만들어 재사용한다.
GRAPH = build_graph()


def run_notify(payload: NotifyInput) -> NotifyOutput:
    """
    /notify 엔드포인트의 진입점.

    실패를 셋으로 나눈다. **이 구분이 이 에이전트의 전부다.**

        모델 호출 실패   → 503 llm_unavailable   BE 가 재시도한다
        발송 실패        → 503 delivery_failed   BE 가 재시도한다 (메일 서버 장애)
        근거 미달        → 200 blocked           교사 확인 큐로. 재시도해도 같다

    셋을 섞으면 교사가 고칠 수 없는 것을 큐에서 보거나, 고쳐야 할 것이 조용히
    재시도만 돌다 끝난다.
    """
    result: NotifyState = GRAPH.invoke({
        "child_id": payload.child_id,
        "child_name": payload.child_name,
        "period_from": payload.period_from,
        "period_to": payload.period_to,
        "insights": payload.insights,
        "notification_id": payload.notification_id,
        "other_child_names": payload.other_child_names,
    })

    if result.get("llm_error"):
        raise LlmUnavailable(result["llm_error"], failure=result.get("llm_failure", "unknown"))

    if result.get("send_error"):
        raise NotifyDeliveryFailed(
            f"child_id={payload.child_id} notification_id={payload.notification_id} "
            f"{result['send_error']}"
        )

    blocked = result.get("blocked_lines") or []
    if blocked:
        status = "blocked"
    elif result.get("passed"):
        status = "sent"
    else:
        # 승인된 인사이트가 없었거나 모델이 아무 줄도 안 냈다. 막힌 것이 아니다.
        status = "nothing_to_send"

    return NotifyOutput(
        child_id=payload.child_id,
        notification_id=payload.notification_id,
        status=status,
        message_id=result.get("message_id"),
        duplicate=bool(result.get("duplicate")),
        blocked_lines=[BlockedLine(**b) for b in blocked],
        llm_called=bool(result.get("llm_called")),
    )
