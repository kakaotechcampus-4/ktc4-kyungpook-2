from typing import TypedDict

from .schemas import ApprovedInsight


class NotifyState(TypedDict, total=False):
    child_id: int
    child_name: str
    period_from: str
    period_to: str
    insights: list[ApprovedInsight]
    notification_id: str
    other_child_names: list[str]

    subject: str
    lines: list[str]
    body: str
    html: str

    passed: bool
    blocked_lines: list[dict]

    message_id: str | None
    no_recipient: bool
    duplicate: bool
    send_error: str | None
