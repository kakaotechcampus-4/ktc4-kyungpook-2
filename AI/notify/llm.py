# AI/notify/llm.py
"""
알림이 쓰는 예외.

**모델을 부르지 않는다** (#433). 전에는 여기 Luna 호출이 있었는데, 인사이트가 해요체로
통일되면서 발송 시점에 다시 쓸 일이 없어졌다 (nodes.py 머리말). 남은 것은 발송 실패뿐이다.

파일을 지우지 않고 둔다 — main.py 의 예외 핸들러가 이 클래스를 import 한다.
"""

#: 503 으로 나가는 예외. 모든 에이전트가 같은 클래스를 쓴다 — 따로 두면 main.py 에서
#: 이름이 덮여 핸들러가 한쪽에만 걸린다 (common/errors.py).
from common.errors import LlmUnavailable

AGENT = "notify"


class NotifyDeliveryFailed(LlmUnavailable):
    """
    메일을 보내려다 실패했다 (MCP 호출 실패 · 메일 서버 장애).

    **근거 미달과 반드시 나눈다.** 근거 미달은 200 blocked 로 내려가 교사 확인 큐에 남고,
    이것은 503 이라 BE 가 재시도한다. 둘을 섞으면 교사가 고칠 수 없는 것을 큐에서 보거나,
    고쳐야 할 것이 조용히 재시도만 돌다 끝난다.

    {@code LlmUnavailable} 을 물려받는 것은 main.py 의 같은 핸들러를 쓰려는 것뿐이다.
    LLM 과는 상관이 없다 — reason 이 {@code delivery_failed} 라 BE 는 구별할 수 있다.
    """

    reason = "delivery_failed"
    message = "메일 발송 실패"
