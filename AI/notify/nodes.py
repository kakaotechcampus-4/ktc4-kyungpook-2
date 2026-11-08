# AI/notify/nodes.py
"""
알림 노드 셋.

    compose  승인된 인사이트를 보호자용 해요체로 다시 쓴다 (모델)
    check    쓴 글이 승인된 인사이트 안의 내용인지 대조한다 (코드)
    send     통과한 것만 MCP 로 내보낸다 (도구)

요약의 gather → write → ground 와 같은 모양이다. **모델이 쓰고, 코드가 근거를
다시 본다.** 다른 점은 버리는 방식이다 — 요약은 근거 없는 문장만 빼고 나머지를
내지만, 메일은 한 줄이라도 걸리면 통째로 막는다 (config.BLOCK_WHOLE_MAIL).
한 줄 빠진 메일은 교사가 승인한 적 없는 조합이고, 보낸 뒤에는 되돌릴 수 없다.
"""

import re

from . import config, prompts
from .llm import LlmError, ask_json
from .mcp_client import McpUnavailable, call_tool
from .state import NotifyState

# ── 걸린 이유 (CRITERIA.md §3) ─────────────────────────────────

NO_REFERENCE = "근거없음"
NUMBER_MISMATCH = "숫자불일치"
INSTITUTION_MISMATCH = "기관불일치"
OTHER_CHILD_NAME = "다른아이이름"

#: 본문에서 기관을 가리키는 말. 인사이트의 institution_type 값과 같은 낱말이다.
_INSTITUTION_WORDS = ("학교", "센터", "활동지원사")

#: 아라비아 숫자만 본다. 한글 수사(세 번 · 두어 번)는 요약의 _numbers 가 보는 영역이고,
#: 여기서 다시 구현하면 두 벌이 갈라진다. **못 잡는 것을 적어 둔다** — 메일이 "세 번"을
#: "다섯 번"으로 바꿔 써도 이 검사는 통과한다 (CRITERIA.md §5 남은 한계).
_DIGITS = re.compile(r"\d+")


def compose(state: NotifyState) -> dict:
    """승인된 인사이트를 보호자에게 보낼 글로 다시 쓴다."""
    insights = state.get("insights") or []
    if not insights:
        # 보낼 것이 없다. 모델을 부르지 않는다 — 빈 재료로 부르면 모델이 지어낸다.
        return {"llm_called": False, "subject": "", "raw_lines": []}

    messages = [
        {"role": "system", "content": prompts.SYSTEM},
        {"role": "user", "content": prompts.user_message(
            state["child_name"], state["period_from"], state["period_to"], insights)},
    ]
    try:
        result = ask_json(messages)
    except LlmError as exc:
        print(f"[notify] 메일 본문 작성 실패 ({exc.kind}): {exc}")
        return {"llm_called": True, "llm_error": str(exc), "llm_failure": exc.kind}

    raw_lines = [ln for ln in (result.data.get("lines") or []) if isinstance(ln, dict)]
    return {
        "llm_called": True,
        "subject": str(result.data.get("subject") or "").strip(),
        "raw_lines": raw_lines,
    }


def _line_reasons(line: dict, insights: list, other_names: list[str]) -> list[str]:
    """줄 하나가 걸린 이유. 빈 목록이면 통과다."""
    reasons: list[str] = []
    text = str(line.get("text") or "")

    # ① 어느 인사이트에서 나왔는지 가리켰나. 가리키지 않으면 대조할 상대가 없다.
    index = line.get("insight_index")
    source = None
    if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < len(insights):
        if config.REQUIRE_INSIGHT_REFERENCE:
            reasons.append(NO_REFERENCE)
    else:
        source = insights[index]

    # ② 숫자. 인사이트에 없는 숫자가 메일에 있으면 지어낸 것이다.
    if config.CHECK_NUMBERS and source is not None:
        if set(_DIGITS.findall(text)) - set(_DIGITS.findall(source.content)):
            reasons.append(NUMBER_MISMATCH)

    # ③ 기관. 센터 기록뿐인 인사이트에 "학교에서는" 을 붙이면 보호자가 사실을 잘못 안다.
    if config.CHECK_INSTITUTION_TYPES and source is not None:
        said = {w for w in _INSTITUTION_WORDS if w in text}
        if said - set(source.institution_types):
            reasons.append(INSTITUTION_MISMATCH)

    # ④ 다른 아이 이름. 메일은 기관 경계 밖으로 나가는 유일한 길이다.
    #    인사이트 본문이 아니라 **메일 글자**를 본다 — 모델이 어디서 가져왔든 나가면 안 된다.
    if config.CHECK_OTHER_CHILD_NAMES and any(name and name in text for name in other_names):
        reasons.append(OTHER_CHILD_NAME)

    return reasons


def check(state: NotifyState) -> dict:
    """
    쓴 글이 승인된 인사이트 안의 내용인지 대조한다. 여기가 메일을 막는 자리다.

    통과 못 한 줄이 하나라도 있으면 passed=False 고, 그래프는 send 로 가지 않는다.
    """
    lines = state.get("raw_lines") or []
    insights = state.get("insights") or []
    other_names = state.get("other_child_names") or []

    if not lines:
        # 모델이 아무 줄도 안 냈다. 보낼 것이 없는 것과 같다 — 막힌 것이 아니다.
        return {"passed": False, "blocked_lines": [], "body": ""}

    blocked = []
    for line in lines:
        reasons = _line_reasons(line, insights, other_names)
        if reasons:
            blocked.append({"text": str(line.get("text") or ""), "reasons": reasons})

    if blocked and config.BLOCK_WHOLE_MAIL:
        print(f"[notify] 발송 막음 — 걸린 줄 {len(blocked)}/{len(lines)}")
        return {"passed": False, "blocked_lines": blocked, "body": ""}

    body = "\n".join(str(ln.get("text") or "") for ln in lines)
    return {"passed": True, "blocked_lines": [], "body": body}


def send(state: NotifyState) -> dict:
    """
    MCP 로 메일을 보낸다. 수신자는 넘기지 않는다 — child_id 로 서버가 찾는다.

    발송 실패는 **근거 미달과 다르다.** send_error 를 채워 503 으로 올린다 (graph.py).
    """
    try:
        result = call_tool("send_email", {
            "child_id": state["child_id"],
            "notification_id": state["notification_id"],
            "subject": state.get("subject") or "",
            "body": state.get("body") or "",
        })
    except McpUnavailable as exc:
        print(f"[notify] 발송 실패 ({exc.kind}): {exc}")
        return {"send_error": str(exc), "message_id": None, "duplicate": False}

    # 받을 사람이 없는 것은 장애가 아니다. 보호자가 메일을 안 받기로 했거나 아직 안 넣었다.
    if result.get("status") == "no_recipient":
        return {"message_id": None, "duplicate": False}

    return {
        "message_id": result.get("message_id"),
        "duplicate": bool(result.get("duplicate")),
    }
