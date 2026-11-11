# AI/mcp_servers/
"""
MCP 서버들. **디렉터리 이름을 mcp 로 두면 안 된다** — pip 의 `mcp` 패키지를 가려서
`from mcp.server...` 가 이 폴더를 먼저 찾는다. import 는 성공하고 속만 비어 있어
"모듈은 있는데 이름이 없다" 로 터진다. common/errors.py 의 이름 충돌과 같은 종류다.
"""
