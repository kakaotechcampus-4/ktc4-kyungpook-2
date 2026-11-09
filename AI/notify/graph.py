# AI/notify/graph.py
"""
Notify Agent 그래프 조립 (#419 · #433).

    START → assemble → check ─┬─(통과)─→ send → END
                              └─(막힘)──────────→ END

**조건부 엣지가 이 그래프의 핵심이다.** "다른 아이 이름이 섞이면 메일이 안 나간다" 가
그래프에 보인다. 보낼지 말지는 코드가 정한다.

모델을 부르지 않는다 (nodes.py 머리말). 인사이트가 해요체로 통일되면서 발송 시점에 다시 쓸
일이 없어졌고, 다시 쓰면 교사가 승인한 문장이 아니게 된다.
"""

from typing import Literal

from langgraph.graph import END, START, StateGraph

from .llm import NotifyDeliveryFailed
from .nodes import assemble, check, send
from .schemas import BlockedLine, NotifyInput, NotifyOutput
from .state import NotifyState


def route_after_check(state: NotifyState) -> Literal["send", "__end__"]:
    """
    메일을 내보낼지 정한다. 이 한 줄이 아동 정보가 바깥으로 나가는 유일한 관문이다.

    통과 못 하면 보내지 않고 끝낸다. 걸린 줄만 빼고 보내지 않는다 —
    한 줄 빠진 메일은 교사가 승인한 적 없는 조합이다 (nodes.py).
    """
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


#: 그래프는 상태를 갖지 않으므로 한 번만 만들어 재사용한다.
GRAPH = build_graph()


def run_notify(payload: NotifyInput) -> NotifyOutput:
    """
    /notify 엔드포인트의 진입점.

    끝나는 길을 넷으로 나눈다. **이 구분이 이 에이전트의 전부다.**

        200 sent            나갔다
        200 no_recipient    글은 통과했는데 받을 사람이 없었다
        200 blocked         다른 아이 이름이 섞였다 → 교사 확인. 재시도해도 같다
        200 nothing_to_send 승인된 인사이트가 없었다
        503 delivery_failed 메일 서버 장애 → BE 가 재시도한다

    **llm_unavailable 은 더 이상 없다** — 모델을 부르지 않는다 (#433).
    근거 미달을 503 으로 올리면 BE 가 영원히 재시도한다. 반대로 메일 서버 장애를 blocked 로
    내리면 교사가 고칠 수 없는 것을 큐에서 본다.
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
        # 승인된 인사이트가 없었다. 막힌 것이 아니다.
        status = "nothing_to_send"

    return NotifyOutput(
        child_id=payload.child_id,
        notification_id=payload.notification_id,
        status=status,
        message_id=result.get("message_id"),
        duplicate=bool(result.get("duplicate")),
        blocked_lines=[BlockedLine(**b) for b in blocked],
    )
