# AI/evals/scripts/notify/roundtrip.py
"""
MCP 서버를 실제로 띄워 왕복을 확인한다. Luna 는 부르지 않는다.

    PYTHONPATH=. python evals/scripts/notify/roundtrip.py

test_gate.py 는 MCP 를 가짜로 바꿔 **관문**만 본다. 여기서는 반대로 관문을 빼고
**서버가 실제로 뜨고 도구가 불리는지**를 본다. 둘이 나뉘어야 "검사는 되는데
서버가 안 뜬다" 가 조용히 지나가지 않는다.

NOTIFY_SMTP_HOST 가 없으면 서버가 보내는 시늉만 하므로 메일은 나가지 않는다.
"""
import os, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
os.environ.setdefault("NOTIFY_MCP_COMMAND", sys.executable)
from notify import mcp_client

print("① 도구 목록")
for t in mcp_client.list_tools():
    print(f"   - {t['name']}: {t['description'][:50]}...")
    print(f"     parameters keys: {list((t['parameters'] or {}).get('properties', {}))}")

print("\n② 첫 발송")
r1 = mcp_client.call_tool("send_email", {
    "child_id": 12, "notification_id": "run-777",
    "subject": "이번 주 김지후", "body": "잘 지냈어요."})
print("  ", r1)

print("\n③ 같은 notification_id 로 다시 (멱등성)")
r2 = mcp_client.call_tool("send_email", {
    "child_id": 12, "notification_id": "run-777",
    "subject": "이번 주 김지후", "body": "잘 지냈어요."})
print("  ", r2)
assert r2["message_id"] == r1["message_id"], "message_id 가 달라졌다"
assert r2["duplicate"] is True, "중복으로 표시되지 않았다"

print("\n④ 받을 사람 없는 아이")
print("  ", mcp_client.call_tool("send_email", {
    "child_id": 999, "notification_id": "run-888", "subject": "x", "body": "y"}))

print("\n⑤ 발송 상태 조회")
print("  ", mcp_client.call_tool("check_delivery", {"message_id": r1["message_id"]}))
print("  ", mcp_client.call_tool("check_delivery", {"message_id": "없는-id"}))
print("\n모두 통과")
