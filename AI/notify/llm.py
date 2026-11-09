from common.errors import LlmUnavailable

AGENT = "notify"


class NotifyDeliveryFailed(LlmUnavailable):

    reason = "delivery_failed"
    message = "메일 발송 실패"
