# AI/notify/config.py
"""
알림 판정에 쓰는 상수 (#419).

요약·인사이트와 같은 자리다 — **무엇을 막는가**가 전부 불리언이다.
기준은 CRITERIA.md 에 있다.

알림이 앞의 넷과 다른 점 하나: **버리지 않고 통째로 막는다.**
요약은 근거 없는 문장만 빼고 나머지를 낸다. 메일은 뺄 수 없다 — 한 줄만 빠진
메일은 교사가 승인한 적 없는 조합이고, 보낸 뒤에는 되돌릴 수 없다.
"""

import os

from common.luna import CHAT_PATH

LUNA_CHAT_PATH = CHAT_PATH

#: 메일 본문을 쓰는 호출의 제한시간. 인사이트 몇 개를 다시 쓰는 일이라 요약보다 짧다.
LUNA_TIMEOUT = float(os.environ.get("NOTIFY_LUNA_TIMEOUT", "60"))

#: 생각에 토큰을 다 써서 답이 빈 채로 올 때 한 번만 낮춰 다시 부른다 (요약과 같다).
LUNA_REASONING_EFFORT_ON_OVERFLOW = "low"

# ── 발송 전 검사 (check 노드) ──────────────────────────────────

#: 줄마다 어느 인사이트에서 나왔는지 가리켜야 한다. 안 가리키면 막는다.
#: 가리키지 않은 줄은 대조할 상대가 없어 "검사를 통과했다" 고 말할 수 없다.
REQUIRE_INSIGHT_REFERENCE = True

#: 줄에 나온 숫자가 그 인사이트에 없으면 막는다. 환각이 가장 눈에 띄는 자리다.
CHECK_NUMBERS = True

#: 줄이 말한 기관 종류가 그 인사이트의 근거 기관에 없으면 막는다.
#: "학교에서는" 을 센터 근거만 있는 인사이트에 붙이면 보호자가 사실을 잘못 안다.
CHECK_INSTITUTION_TYPES = True

#: 다른 아이 이름이 본문에 있으면 막는다. 메일은 기관 경계 밖으로 나가는 유일한 길이다.
CHECK_OTHER_CHILD_NAMES = True

#: 제목도 본문과 같은 기준으로 본다. 제목은 모델이 쓰는데 검사에서 빠져 있었다 (리뷰 #430).
#: 받은편지함 목록에 제일 먼저 보이는 글자라, 여기로 새면 열어 보지 않아도 샌다.
CHECK_SUBJECT = True

#: 승인된 인사이트가 하나도 빠지지 않고 메일에 담겼는지 본다 (리뷰 #430).
#: "한 줄 빠진 메일은 승인한 적 없는 조합" 이라면, 한 건 통째로 빠진 메일도 마찬가지다.
REQUIRE_ALL_INSIGHTS = True

#: 한 줄이라도 걸리면 **메일 전체를 막는다**. 걸린 줄만 빼지 않는다.
BLOCK_WHOLE_MAIL = True

# ── MCP ────────────────────────────────────────────────────────

#: MCP 서버를 띄우는 명령. 기본은 같은 레포의 stdio 서버다.
MCP_COMMAND = os.environ.get("NOTIFY_MCP_COMMAND", "python")
MCP_ARGS = os.environ.get("NOTIFY_MCP_ARGS", "-m mcp_servers.itda_notify").split()

#: MCP 도구 호출 제한시간.
MCP_TIMEOUT = float(os.environ.get("NOTIFY_MCP_TIMEOUT", "30"))
