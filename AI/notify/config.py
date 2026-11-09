import os

CHECK_OTHER_CHILD_NAMES = True

BLOCK_WHOLE_MAIL = True

MCP_COMMAND = os.environ.get("NOTIFY_MCP_COMMAND", "python")
MCP_ARGS = os.environ.get("NOTIFY_MCP_ARGS", "-m mcp_servers.itda_notify").split()

MCP_TIMEOUT = float(os.environ.get("NOTIFY_MCP_TIMEOUT", "30"))
