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
