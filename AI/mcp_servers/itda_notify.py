# AI/mcp_servers/itda_notify.py
"""
itda-notify — 보호자에게 메일을 보내는 MCP 서버 (#419).

도구는 둘이다.
  send_email      승인된 인사이트 메일을 보낸다. message_id 를 돌려준다
  check_delivery  그 메일이 어떻게 됐는지 본다

## 수신자를 인자로 받지 않는다

도구 인자에 이메일 주소가 없다. child_id 만 받고 **주소는 서버가 찾는다.**
insight/tools.py 의 find_claims 와 같은 원칙이다 — 모델은 무엇을 보낼지만 정하고,
어디로 갈지는 코드가 정한다. 주소를 인자로 두면 모델 문맥에 주소가 들어오고,
그 순간 "모델이 지어낸 주소로 아이 기록이 나간다" 가 가능해진다.

지금은 파일(NOTIFY_RECIPIENTS)에서 찾는다. #420 이 끝나면 BE 에 묻는다.

## stdout 으로 아무것도 찍지 않는다

stdio MCP 는 stdout 이 프로토콜 채널이다. print() 한 줄이 JSON-RPC 스트림을 깨고,
클라이언트는 "서버가 응답을 안 준다" 로만 본다. 로그는 전부 stderr 로 간다.
"""

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

# mcp 2.x 에서 FastMCP 가 MCPServer 로 바뀌었다. 1.x 를 쓰면 이 import 가 없다.
from mcp.server.mcpserver import MCPServer

mcp = MCPServer("itda-notify")

#: child_id → 보호자 주소. {"12": ["a@example.com"]} 모양이다.
#: ⚠️ 이 파일에는 실제 주소가 들어간다. 레포에 커밋하지 않는다.
RECIPIENTS_PATH = os.environ.get("NOTIFY_RECIPIENTS", "evals/generated/notify/recipients.json")

#: 보낸 기록. 멱등성(같은 notification_id 는 한 번만)과 check_delivery 가 읽는다.
#: 본문은 남기지 않는다 — 아이 기록이다. 식별자와 상태만 적는다.
SENT_LOG_PATH = os.environ.get("NOTIFY_SENT_LOG", "evals/generated/notify/sent.json")


def _log(message: str) -> None:
    """stdout 은 프로토콜 채널이라 쓰면 안 된다 (모듈 머리말)."""
    print(f"[itda-notify] {message}", file=sys.stderr, flush=True)


@contextmanager
def _lock():
    """
    발송 기록을 읽고 쓰는 동안 잠근다 (리뷰 #430).

    서버는 호출마다 새 프로세스로 뜨므로 프로세스 안의 락으로는 모자란다. 파일 락을 쓴다.
    없으면 같은 {@code notification_id} 가 동시에 두 번 왔을 때 둘 다 "기록이 없다" 를 보고
    각자 보낸다 — 보호자가 같은 메일을 두 통 받는다.
    """
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
    """
    그 아이의 보호자 주소. 없으면 빈 목록이다.

    #420 이 끝나면 여기서 BE 를 부른다. 그때도 **동의한 보호자만** 돌려받아야 한다 —
    주소를 아는 것과 보내도 되는 것은 다르다.
    """
    return _load(RECIPIENTS_PATH).get(str(child_id), [])


#: 가짜 발송일 때 메일을 적어 두는 곳. 시연에서 "무엇이 나갔나" 를 눈으로 본다.
#: evals/generated/ 아래라 gitignore 된다 — 아이 기록이 들어 있다.
OUTBOX_DIR = os.environ.get("NOTIFY_OUTBOX", "evals/generated/notify/outbox")


def _fake_deliver(addresses: list[str], subject: str, body: str) -> None:
    """
    SMTP 설정이 없을 때. 보내지 않고 파일로 적는다.

    **stderr 에는 주소를 찍지 않는다** — 로그는 남는 자리라 개수만 적는다.
    파일에는 적는다. 시연에서 "누구에게 무엇이 갔나" 를 확인해야 하고,
    그 파일은 gitignore 된 자리에만 생긴다.
    """
    path = Path(OUTBOX_DIR) / f"{datetime.now(timezone.utc):%Y%m%dT%H%M%S}-{uuid.uuid4().hex[:6]}.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"To: {', '.join(addresses)}\nSubject: {subject}\n\n{body}\n", encoding="utf-8")
    _log(f"[가짜 발송] 수신 {len(addresses)}명 · 제목 {subject!r} · {path}")


def _smtp_deliver(addresses: list[str], subject: str, body: str) -> None:
    """
    실제 발송 (#419 5단계).

    설정은 환경변수로만 받는다 — 비밀번호가 코드나 인자에 들어가면 안 된다.
        NOTIFY_SMTP_HOST  smtp.naver.com
        NOTIFY_SMTP_PORT  587 (STARTTLS) · 465 (SSL)
        NOTIFY_SMTP_USER  보내는 계정
        NOTIFY_SMTP_PASS  앱 비밀번호
        NOTIFY_SMTP_FROM  보내는 주소 (없으면 USER)

    **받는 사람끼리 서로 보이면 안 된다.** 보호자 여럿이면 한 통씩 따로 보낸다 —
    To 에 몰아 넣으면 다른 보호자의 주소가 노출된다.
    """
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
            smtp.send_message(message)
    _log(f"[발송] 수신 {len(addresses)}명 · 제목 {subject!r}")


def _deliver(addresses: list[str], subject: str, body: str) -> None:
    """SMTP 설정이 있으면 진짜로, 없으면 파일로. 시연과 운영이 같은 길을 탄다."""
    if os.environ.get("NOTIFY_SMTP_HOST"):
        _smtp_deliver(addresses, subject, body)
    else:
        _fake_deliver(addresses, subject, body)


@mcp.tool()
def send_email(child_id: int, notification_id: str, subject: str, body: str) -> dict:
    """
    승인된 주간 인사이트를 그 아이의 보호자에게 메일로 보낸다.

    같은 notification_id 로 다시 부르면 **보내지 않고** 처음 message_id 를 그대로 돌려준다.
    재시도가 보호자에게 메일 세 통이 되면 안 된다.

    Args:
        child_id: 아이 번호. 수신자는 이 값으로 서버가 찾는다 (주소를 넘기지 않는다).
        notification_id: 이 승인 묶음을 가리키는 BE 의 키. 멱등성 기준이다.
        subject: 메일 제목.
        body: 메일 본문. 발송 전 검사를 통과한 글만 온다.
    """
    with _lock():
        sent = _load(SENT_LOG_PATH)
        done = sent.get(notification_id)
        if done and not done.get("pending"):
            _log(f"이미 보낸 묶음이다 notification_id={notification_id} — 다시 보내지 않는다")
            return {**{k: v for k, v in done.items() if k != "delivered"}, "duplicate": True}

        addresses = _recipients(child_id)
        if not addresses:
            # 주소가 없는 것은 장애가 아니다. 보호자가 메일을 안 받기로 했거나 아직 안 넣었다.
            _log(f"받을 사람이 없다 child_id={child_id}")
            return {"status": "no_recipient", "message_id": None, "duplicate": False}

        # 자리를 먼저 잡는다. 같은 묶음이 동시에 두 번 와도 뒤의 호출은 이 행을 보고 비켜난다.
        # 이 자리를 잡기 전에 보내면 두 호출이 각자 보낸다 (리뷰 #430).
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

    # 보호자별로 보내고 한 명 끝날 때마다 적는다. 중간에 SMTP 가 끊겨도, 다시 부르면
    # 이미 받은 보호자는 건너뛴다 — 예전에는 기록 없이 503 이 되어 첫 보호자가 두 통을 받았다.
    remaining = [a for a in addresses if a not in record.get("delivered", [])]
    for address in remaining:
        _deliver([address], subject, body)
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
            # delivered 에는 주소가 들어 있다. 돌려주지 않는다 — 모델 문맥에 주소가 들어가면 안 된다.
            visible = {k: v for k, v in record.items() if k != "delivered"}
            return {**visible, "notification_id": notification_id}
    return {"status": "unknown", "message_id": message_id}


if __name__ == "__main__":
    mcp.run()
