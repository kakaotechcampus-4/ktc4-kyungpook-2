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

    # ── compose 가 채운다 ──
    llm_called: bool
    #: 값이 있으면 run_notify 가 503 으로 올린다.
    llm_error: str | None
    #: 실패 종류 (timeout · empty_response …). common/errors.py FAILURE_KINDS.
    llm_failure: str
    subject: str
    #: 모델이 쓴 줄들. 아직 아무것도 대조하지 않은 상태다.
    #: [{"text": "...", "insight_index": 0}, ...]
    raw_lines: list[dict]

    # ── check 가 채운다 ──
    #: 검사를 통과했나. **한 줄이라도 걸리면 False** — 걸린 줄만 빼지 않는다.
    passed: bool
    #: 걸린 줄과 이유. 교사 화면이 읽는다.
    blocked_lines: list[dict]
    #: 통과했을 때 실제로 나갈 본문. 줄을 이어 만든다.
    body: str

    # ── send 가 채운다 ──
    message_id: str | None
    #: 같은 notification_id 로 이미 보낸 적이 있다.
    duplicate: bool
    #: 발송 자체가 실패했다. 값이 있으면 503 으로 올린다 — 근거 미달(200)과 다르다.
    send_error: str | None
