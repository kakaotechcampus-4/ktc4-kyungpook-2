# AI/notify/nodes.py
"""
알림 노드 셋.

    assemble  승인된 인사이트를 이어 메일을 만든다 (코드)
    check     나갈 글에 다른 아이 이름이 없는지 본다 (코드)
    send      통과한 것만 MCP 로 내보낸다 (도구)

## 모델을 부르지 않는다 (#433)

처음에는 compose 노드가 Luna 를 불러 교사용 글을 보호자용으로 다시 썼다. 인사이트가 해요체로
통일되면서(#433) 할 일이 없어졌다 — 해요체를 해요체로 다시 쓰는 호출이었다.

**중복이라서만 뺀 것이 아니다.** 승인 뒤에 다시 쓰면 "교사가 승인한 문장 = 보호자가 받는 문장"
이 또 깨진다. 통일로 얻은 것을 발송 단계가 도로 잃는다 (#430 리뷰). 지금은 승인된 글자를
코드가 그대로 이어 붙이므로 구조적으로 같다.

## 그래도 check 는 남는다

숫자·기관 검사는 의미가 없어졌다 — 승인본을 글자 그대로 복사하니 어긋날 수가 없다.
**다른 아이 이름 검사만 남고, 그게 유일한 코드 방어선이다.** 인사이트 입력에는 다른 아이 이름
목록이 없어서(`InsightInput`) AI 가 그 검사를 못 한다 — 프롬프트 규칙뿐이다.

⚠️ 메일만 지킨다. 같은 글이 보호자 앱 화면에는 이 검사 없이 그대로 뜬다 (CRITERIA §3).
"""

from . import config, template
from .mcp_client import McpUnavailable, call_tool
from .state import NotifyState

# ── 걸린 이유 (CRITERIA.md §3) ─────────────────────────────────

OTHER_CHILD_NAME = "다른아이이름"


def _date(value: str) -> str:
    """{@code "2026-04-01"} → {@code "4월 1일"}. 메일에 넣는 사람이 읽는 날짜다."""
    parts = (value or "").split("-")
    if len(parts) != 3:
        return value
    return f"{int(parts[1])}월 {int(parts[2])}일"


def assemble(state: NotifyState) -> dict:
    """
    승인된 인사이트를 이어 메일을 만든다. **모델을 부르지 않는다.**

    **보호자 리포트 화면(G-36)과 같은 모양으로 적는다** — 기관·기간, "기관들이 함께 본 것",
    "이 글의 근거"(상황·대처 문장)를 화면과 같은 순서로 둔다. 메일을 읽고 앱에 들어온 보호자가
    같은 글을 다시 찾을 수 있어야 한다.

    인사이트 본문과 근거 문장은 손대지 않고 그대로 옮긴다 — 한 글자라도 고치면 교사가 승인한
    글이 아니다. 인사말·맺음말·구분선은 코드가 쓴다. 아이 기록이 아니라 틀이라 승인 대상이 아니다.
    """
    insights = state.get("insights") or []
    if not insights:
        return {"subject": "", "body": "", "lines": []}

    name = state["child_name"]
    period = f"{_date(state['period_from'])} ~ {_date(state['period_to'])}"

    #: 검사(check)가 훑을 글자. 메일에 실제로 나가는 것만 담는다.
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
    # 글자가 같은 두 벌을 만든다. HTML 은 보기 좋은 쪽, text 는 HTML 을 못 그리는 클라이언트용이다.
    # 둘 다 승인된 글자만 담으므로 check 는 lines 하나만 훑으면 된다.
    return {
        "subject": f"[잇다] {name} 이번 주 소식 ({period})",
        "body": body,
        "html": template.render(name, state["period_from"], state["period_to"], insights),
        "lines": lines,
    }


def check(state: NotifyState) -> dict:
    """
    나갈 글에 다른 아이 이름이 없는지 본다. 여기가 메일을 막는 유일한 자리다.

    **인사이트 본문이 아니라 메일 글자를 본다.** 승인된 글에 이름이 섞여 있어도 기관 경계 밖으로
    나가면 안 된다 — 승인은 그 기관 안에서 한 판단이고, 메일은 밖으로 나가는 길이다.

    한 줄이라도 걸리면 메일 전체를 막는다 (config.BLOCK_WHOLE_MAIL). 걸린 줄만 빼면 교사가
    승인한 적 없는 조합이 되고, 보낸 뒤에는 되돌릴 수 없다.
    """
    lines = state.get("lines") or []
    subject = state.get("subject") or ""
    other_names = [n for n in (state.get("other_child_names") or []) if n]

    if not lines:
        # 보낼 것이 없다. 막힌 것이 아니다.
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
    """
    MCP 로 메일을 보낸다. 수신자는 넘기지 않는다 — child_id 로 서버가 찾는다.

    발송 실패는 근거 미달과 다르다. send_error 를 채워 503 으로 올린다 (graph.py).
    """
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

    # 받을 사람이 없는 것은 장애가 아니다. 그래도 sent 로 묶지 않는다 — 메일은 안 나갔다 (#430 리뷰).
    if result.get("status") == "no_recipient":
        print(f"[notify] 받을 보호자가 없다 child_id={state['child_id']}")
        return {"message_id": None, "duplicate": False, "no_recipient": True}

    return {
        "message_id": result.get("message_id"),
        "duplicate": bool(result.get("duplicate")),
        "no_recipient": False,
    }
