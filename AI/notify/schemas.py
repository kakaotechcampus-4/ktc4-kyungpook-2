# AI/notify/schemas.py
"""
Notify Agent 의 입출력 계약 (#419).

앞의 넷과 다른 점:

1. **입력이 승인된 인사이트다.** Gate 2 를 통과한 것만 온다. 여기서 새로 판정하지 않는다.
2. **모델이 쓴 글이 그대로 나가지 않는다.** 승인된 인사이트 안의 내용인지 코드가 다시 본다.
3. **수신자를 받지 않는다.** 이메일 주소는 이 계약에 없다 — child_id 로 MCP 서버가 찾는다.
   모델 프롬프트에도, 도구 인자에도, 로그에도 주소가 없다.
"""

from typing import Literal

from pydantic import BaseModel, Field


# ── 입력 ────────────────────────────────────────────────────────


class ApprovedInsight(BaseModel):
    """Gate 2 에서 승인된 인사이트 하나. insight.schemas.Insight 에서 메일에 필요한 칸만 받는다."""

    #: 보호자에게 나갈 글의 재료. 이 안에 없는 내용은 메일에 못 쓴다 (check 노드).
    content: str

    #: 근거를 낸 기관 종류 (학교 / 센터 / 활동지원사). "학교에서는" 을 쓸 수 있는지 가른다.
    institution_types: list[str] = Field(default_factory=list)

    #: 여러 기관 기록이 재료인가. 메일에서 기관을 나눠 쓸지 정하는 데만 쓴다.
    cross_institution: bool = False


class NotifyInput(BaseModel):
    """
    Gate 2 승인 직후 BE 가 보낸다.

    ⚠️ 보호자 이메일은 여기 없다. BE 는 child_id 만 준다 (#420 이 끝나면 MCP 서버가 BE 에 묻는다).
    """

    child_id: int
    child_name: str
    period_from: str
    period_to: str

    #: 승인된 인사이트. 비어 있으면 보낼 것이 없다 — 200 nothing_to_send 로 끝낸다.
    insights: list[ApprovedInsight] = Field(default_factory=list)

    #: 같은 승인 묶음을 가리키는 BE 의 키. **같은 값으로 두 번 오면 메일은 한 번만 나간다.**
    #: 재시도가 보호자에게 메일 세 통이 되면 안 된다.
    notification_id: str

    #: 본문에 남았는지 대조할 다른 아이 이름. 프롬프트에는 넣지 않는다 (요약과 같다).
    other_child_names: list[str] = Field(default_factory=list)


# ── 출력 ────────────────────────────────────────────────────────

#: sent            메일이 나갔다
#: no_recipient    보낼 글은 통과했는데 **받을 사람이 없었다**
#: blocked         근거 검사에 걸려 **보내지 않았다**. 교사 확인이 필요하다
#: nothing_to_send 승인된 인사이트가 없어 보낼 것이 없었다
#:
#: 발송 실패는 여기 없다 — 503 으로 올린다 (llm.py NotifyDeliveryFailed).
#: 모델 고장(llm_unavailable)도 없다 — 모델을 부르지 않는다 (#433).
#: 근거 미달(200 blocked)과 메일 서버 장애(503)를 나눠야 BE 가 다르게 처리한다.
#:
#: **no_recipient 를 sent 로 묶으면 안 된다** (리뷰 #430). 장애는 아니지만 메일은 안 나갔다.
#: 둘을 합치면 BE 가 SENT 로 적고, 교사는 "보냈다" 고 믿는다 — 보호자가 이메일을 아직 안 넣은
#: 경우가 조용히 성공으로 쌓인다.
NotifyStatus = Literal["sent", "no_recipient", "blocked", "nothing_to_send"]


class BlockedLine(BaseModel):
    """검사에 걸린 줄 하나. 교사 화면이 "왜 안 나갔나" 를 보여 주는 재료다."""

    text: str
    #: 걸린 이유 (CRITERIA.md §3). 여러 개일 수 있다.
    reasons: list[str]


class NotifyOutput(BaseModel):
    child_id: int
    notification_id: str
    status: NotifyStatus

    #: 보낸 메일의 식별자. MCP 서버가 준다. status=sent 일 때만 값이 있다.
    message_id: str | None = None

    #: 같은 notification_id 로 이미 보낸 적이 있어 다시 보내지 않았다.
    #: status 는 sent 그대로다 — BE 입장에서 결과가 같다.
    duplicate: bool = False

    #: status=blocked 일 때 왜 막혔는지. 비어 있지 않으면 교사에게 확인을 요청한다.
    blocked_lines: list[BlockedLine] = Field(default_factory=list)

