from typing import Literal

from langgraph.graph import END, START, StateGraph

from .llm import NotifyDeliveryFailed
from .nodes import assemble, check, send
from .schemas import BlockedLine, NotifyInput, NotifyOutput
from .state import NotifyState


def route_after_check(state: NotifyState) -> Literal["send", "__end__"]:
    return "send" if state.get("passed") else END


def build_graph():
    builder = StateGraph(NotifyState)

    builder.add_node("assemble", assemble)
    builder.add_node("check", check)
    builder.add_node("send", send)

    builder.add_edge(START, "assemble")
    builder.add_edge("assemble", "check")
    builder.add_conditional_edges("check", route_after_check, {"send": "send", END: END})
    builder.add_edge("send", END)

    return builder.compile()


GRAPH = build_graph()


def run_notify(payload: NotifyInput) -> NotifyOutput:
    result: NotifyState = GRAPH.invoke({
        "child_id": payload.child_id,
        "child_name": payload.child_name,
        "period_from": payload.period_from,
        "period_to": payload.period_to,
        "insights": payload.insights,
        "notification_id": payload.notification_id,
        "other_child_names": payload.other_child_names,
    })

    if result.get("send_error"):
        raise NotifyDeliveryFailed(
            f"child_id={payload.child_id} notification_id={payload.notification_id} "
            f"{result['send_error']}"
        )

    blocked = result.get("blocked_lines") or []
    if blocked:
        status = "blocked"
    elif result.get("no_recipient"):
        status = "no_recipient"
    elif result.get("passed"):
        status = "sent"
    else:
        status = "nothing_to_send"

    return NotifyOutput(
        child_id=payload.child_id,
        notification_id=payload.notification_id,
        status=status,
        message_id=result.get("message_id"),
        duplicate=bool(result.get("duplicate")),
        blocked_lines=[BlockedLine(**b) for b in blocked],
    )
