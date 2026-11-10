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

# ── 발송 전 검사 (check 노드) ──────────────────────────────────

#: 모델을 부르지 않게 되면서(#433) 숫자·기관·근거번호 검사는 의미가 없어졌다 — 승인본을
#: 글자 그대로 복사하니 어긋날 수가 없다. 남은 것은 이 검사 하나다.

#: 다른 아이 이름이 본문이나 제목에 있으면 막는다.
#:
#: **유일한 코드 방어선이다.** 인사이트 입력에는 다른 아이 이름 목록이 없어서(InsightInput)
#: AI 가 이 검사를 못 한다 — 프롬프트 규칙뿐이다. 메일은 기관 경계 밖으로 나가는 길이라
#: 여기서 한 번 더 본다.
CHECK_OTHER_CHILD_NAMES = True

#: 한 줄이라도 걸리면 **메일 전체를 막는다**. 걸린 줄만 빼지 않는다.
BLOCK_WHOLE_MAIL = True

# ── MCP ────────────────────────────────────────────────────────

#: MCP 서버를 띄우는 명령. 기본은 같은 레포의 stdio 서버다.
MCP_COMMAND = os.environ.get("NOTIFY_MCP_COMMAND", "python")
MCP_ARGS = os.environ.get("NOTIFY_MCP_ARGS", "-m mcp_servers.itda_notify").split()

#: MCP 도구 호출 제한시간.
MCP_TIMEOUT = float(os.environ.get("NOTIFY_MCP_TIMEOUT", "30"))
