# AI/notify/state.py
"""
그래프가 들고 다니는 값들.

summary/state.py 와 같은 이유로 TypedDict 를 쓴다 — StateGraph(dict) 로 두면
노드가 돌려준 dict 가 상태를 통째로 대체해서 앞 노드가 넣은 값이 조용히 사라진다.
"""

from typing import TypedDict

from .schemas import ApprovedInsight


class NotifyState(TypedDict, total=False):
    # ── 입력 (그래프가 도는 동안 바뀌지 않는다) ──
    child_id: int
    child_name: str
    period_from: str
    period_to: str
    insights: list[ApprovedInsight]
    notification_id: str
    #: 본문에 남았는지 대조할 다른 아이 이름. 프롬프트에는 넣지 않는다.
    other_child_names: list[str]

    # ── assemble 이 채운다 (모델을 부르지 않는다, #433) ──
    subject: str
    #: 메일에 들어갈 인사이트 본문들. 승인된 글자 그대로다 — 한 글자도 고치지 않는다.
    lines: list[str]
    #: 실제로 나갈 본문(글자만). HTML 을 못 그리는 클라이언트가 이걸 본다.
    body: str
    #: 같은 내용의 HTML. 보는 쪽이 HTML 을 그리면 이게 보인다 (template.py).
    html: str

    # ── check 가 채운다 ──
    #: 검사를 통과했나. **한 줄이라도 걸리면 False** — 걸린 줄만 빼지 않는다.
    passed: bool
    #: 걸린 줄과 이유. 교사 화면이 읽는다.
    blocked_lines: list[dict]

    # ── send 가 채운다 ──
    message_id: str | None
    #: 받을 보호자가 없었다. 장애가 아니지만 메일도 안 나갔다 — sent 와 나눈다.
    no_recipient: bool
    #: 같은 notification_id 로 이미 보낸 적이 있다.
    duplicate: bool
    #: 발송 자체가 실패했다. 값이 있으면 503 으로 올린다 — 근거 미달(200)과 다르다.
    send_error: str | None
