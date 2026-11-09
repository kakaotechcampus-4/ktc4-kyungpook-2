import fcntl
import json
import os
import smtplib
import sys
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from email.message import EmailMessage
from pathlib import Path

from mcp.server.mcpserver import MCPServer

mcp = MCPServer("itda-notify")

RECIPIENTS_PATH = os.environ.get("NOTIFY_RECIPIENTS", "evals/generated/notify/recipients.json")

SENT_LOG_PATH = os.environ.get("NOTIFY_SENT_LOG", "evals/generated/notify/sent.json")


def _log(message: str) -> None:
    print(f"[itda-notify] {message}", file=sys.stderr, flush=True)


@contextmanager
def _lock():
    path = Path(SENT_LOG_PATH)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path.with_suffix(".lock"), "w", encoding="utf-8") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def _load(path: str) -> dict:
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def _save(path: str, data: dict) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def _recipients(child_id: int) -> list[str]:
    return _load(RECIPIENTS_PATH).get(str(child_id), [])


OUTBOX_DIR = os.environ.get("NOTIFY_OUTBOX", "evals/generated/notify/outbox")


def _fake_deliver(addresses: list[str], subject: str, body: str, html: str = "") -> None:
    stem = f"{datetime.now(timezone.utc):%Y%m%dT%H%M%S}-{uuid.uuid4().hex[:6]}"
    path = Path(OUTBOX_DIR) / f"{stem}.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"To: {', '.join(addresses)}\nSubject: {subject}\n\n{body}\n", encoding="utf-8")
    if html:
        (path.with_suffix(".html")).write_text(html, encoding="utf-8")
    _log(f"[가짜 발송] 수신 {len(addresses)}명 · 제목 {subject!r} · {path}")


def _smtp_deliver(addresses: list[str], subject: str, body: str, html: str = "") -> None:
    host = os.environ["NOTIFY_SMTP_HOST"]
    port = int(os.environ.get("NOTIFY_SMTP_PORT", "587"))
    user = os.environ.get("NOTIFY_SMTP_USER")
    password = os.environ.get("NOTIFY_SMTP_PASS")
    sender = os.environ.get("NOTIFY_SMTP_FROM") or user
    if not (user and password and sender):
        raise RuntimeError("NOTIFY_SMTP_USER · NOTIFY_SMTP_PASS 가 없다")

    connect = smtplib.SMTP_SSL if port == 465 else smtplib.SMTP
    with connect(host, port, timeout=30) as smtp:
        if port != 465:
            smtp.starttls()
        smtp.login(user, password)
        for address in addresses:
            message = EmailMessage()
            message["Subject"] = subject
            message["From"] = sender
            message["To"] = address
            message.set_content(body)
            if html:
                message.add_alternative(html, subtype="html")
            smtp.send_message(message)
    _log(f"[발송] 수신 {len(addresses)}명 · 제목 {subject!r}")


def _deliver(addresses: list[str], subject: str, body: str, html: str = "") -> None:
    if os.environ.get("NOTIFY_SMTP_HOST"):
        _smtp_deliver(addresses, subject, body, html)
    else:
        _fake_deliver(addresses, subject, body, html)


@mcp.tool()
def send_email(child_id: int, notification_id: str, subject: str, body: str,
               html: str = "") -> dict:
    """
    승인된 주간 인사이트를 그 아이의 보호자에게 메일로 보낸다.

    같은 notification_id 로 다시 부르면 **보내지 않고** 처음 message_id 를 그대로 돌려준다.
    재시도가 보호자에게 메일 세 통이 되면 안 된다.

    Args:
        child_id: 아이 번호. 수신자는 이 값으로 서버가 찾는다 (주소를 넘기지 않는다).
        notification_id: 이 승인 묶음을 가리키는 BE 의 키. 멱등성 기준이다.
        subject: 메일 제목.
        body: 메일 본문(글자). 발송 전 검사를 통과한 글만 온다.
        html: 같은 내용의 HTML. 비우면 글자본만 보낸다.
    """
    with _lock():
        sent = _load(SENT_LOG_PATH)
        done = sent.get(notification_id)
        if done and not done.get("pending"):
            _log(f"이미 보낸 묶음이다 notification_id={notification_id} — 다시 보내지 않는다")
            return {**{k: v for k, v in done.items() if k != "delivered"}, "duplicate": True}

        addresses = _recipients(child_id)
        if not addresses:
            _log(f"받을 사람이 없다 child_id={child_id}")
            return {"status": "no_recipient", "message_id": None, "duplicate": False}

        record = done or {
            "status": "sent",
            "message_id": f"itda-{uuid.uuid4().hex[:16]}",
            "child_id": child_id,
            "recipient_count": len(addresses),
            "sent_at": datetime.now(timezone.utc).isoformat(),
            "delivered": [],
            "pending": True,
        }
        record["pending"] = True
        sent[notification_id] = record
        _save(SENT_LOG_PATH, sent)

    remaining = [a for a in addresses if a not in record.get("delivered", [])]
    for address in remaining:
        _deliver([address], subject, body, html)
        with _lock():
            sent = _load(SENT_LOG_PATH)
            current = sent.get(notification_id, record)
            current.setdefault("delivered", []).append(address)
            sent[notification_id] = current
            _save(SENT_LOG_PATH, sent)
            record = current

    with _lock():
        sent = _load(SENT_LOG_PATH)
        current = sent.get(notification_id, record)
        current["pending"] = False
        sent[notification_id] = current
        _save(SENT_LOG_PATH, sent)
    _log(f"보냈다 notification_id={notification_id} message_id={current['message_id']} "
         f"수신 {len(current.get('delivered', []))}/{len(addresses)}명")
    return {**{k: v for k, v in current.items() if k != "delivered"}, "duplicate": False}


@mcp.tool()
def check_delivery(message_id: str) -> dict:
    """
    보낸 메일의 상태를 본다. 모르는 message_id 면 status=unknown 이다.

    Args:
        message_id: send_email 이 돌려준 값.
    """
    for notification_id, record in _load(SENT_LOG_PATH).items():
        if record.get("message_id") == message_id:
            visible = {k: v for k, v in record.items() if k != "delivered"}
            return {**visible, "notification_id": notification_id}
    return {"status": "unknown", "message_id": message_id}


if __name__ == "__main__":
    mcp.run()
