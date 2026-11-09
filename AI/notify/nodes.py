from . import config, template
from .mcp_client import McpUnavailable, call_tool
from .state import NotifyState

OTHER_CHILD_NAME = "다른아이이름"


def _date(value: str) -> str:
    parts = (value or "").split("-")
    if len(parts) != 3:
        return value
    return f"{int(parts[1])}월 {int(parts[2])}일"


def assemble(state: NotifyState) -> dict:
    insights = state.get("insights") or []
    if not insights:
        return {"subject": "", "body": "", "lines": []}

    name = state["child_name"]
    period = f"{_date(state['period_from'])} ~ {_date(state['period_to'])}"

    lines: list[str] = []
    blocks: list[str] = []

    for insight in insights:
        head = " · ".join(x for x in (insight.institution_name, insight.period_label) if x)
        part = [f"[{head}]"] if head else []
        lines.extend(x for x in (insight.institution_name, insight.period_label) if x)

        part.append("")
        part.append("기관들이 함께 본 것")
        part.append(insight.content)
        lines.append(insight.content)

        rows = [e for e in insight.evidence if e.sentences]
        if rows:
            part.append("")
            part.append("이 글의 근거")
            for entry in rows:
                tail = " · 지난 기록" if entry.looked_up else ""
                label = f"- {entry.institution_name} · {_date(entry.date)} 일지{tail}"
                part.append(label)
                lines.append(entry.institution_name)
                for sentence in entry.sentences:
                    part.append(f"  ({sentence.role}) {sentence.text}")
                    lines.append(sentence.text)
        blocks.append("\n".join(part))

    body = "\n\n".join([
        f"{name} 보호자님, 이번 주 소식입니다.",
        ("\n\n" + "—" * 24 + "\n\n").join(blocks),
        "이 글은 선생님이 확인하고 보낸 내용이에요.\n잇다 앱에서 근거가 된 일지도 함께 볼 수 있어요.",
    ])
    return {
        "subject": f"[잇다] {name} 이번 주 소식 ({period})",
        "body": body,
        "html": template.render(name, state["period_from"], state["period_to"], insights),
        "lines": lines,
    }


def check(state: NotifyState) -> dict:
    lines = state.get("lines") or []
    subject = state.get("subject") or ""
    other_names = [n for n in (state.get("other_child_names") or []) if n]

    if not lines:
        return {"passed": False, "blocked_lines": []}

    if not config.CHECK_OTHER_CHILD_NAMES:
        return {"passed": True, "blocked_lines": []}

    blocked = [
        {"text": text, "reasons": [OTHER_CHILD_NAME]}
        for text in [subject, *lines]
        if any(name in text for name in other_names)
    ]
    if blocked and config.BLOCK_WHOLE_MAIL:
        print(f"[notify] 발송 막음 — 다른 아이 이름이 {len(blocked)}곳")
        return {"passed": False, "blocked_lines": blocked}
    return {"passed": True, "blocked_lines": []}


def send(state: NotifyState) -> dict:
    try:
        result = call_tool("send_email", {
            "child_id": state["child_id"],
            "notification_id": state["notification_id"],
            "subject": state.get("subject") or "",
            "body": state.get("body") or "",
            "html": state.get("html") or "",
        })
    except McpUnavailable as exc:
        print(f"[notify] 발송 실패 ({exc.kind}): {exc}")
        return {"send_error": str(exc), "message_id": None, "duplicate": False}

    if result.get("status") == "no_recipient":
        print(f"[notify] 받을 보호자가 없다 child_id={state['child_id']}")
        return {"message_id": None, "duplicate": False, "no_recipient": True}

    return {
        "message_id": result.get("message_id"),
        "duplicate": bool(result.get("duplicate")),
        "no_recipient": False,
    }
