# AI/notify/mcp_client.py
"""
MCP 클라이언트. AI 서버가 itda-notify 서버의 도구를 부른다.

## 왜 AI 서버가 부르나

BE 가 메일 API 를 직접 부르면 그냥 기능이다. **도구를 쓰는 주체가 에이전트여야**
도구 연동이 된다 (AGENTIC.md 2장 "도구 연동"). 그래서 FastAPI 안에서 MCP 를 부른다.

## Luna 는 MCP 를 모른다

Luna 는 OpenAI 호환 API 라 function_call 만 안다. 그 사이를 이 모듈이 잇는다.

    Luna ←(function_call)→ AI 서버 ←(MCP)→ itda-notify

`to_luna_tools()` 가 MCP 의 inputSchema 를 Luna 의 parameters 로 바꾼다.
지금 notify 그래프는 모델에게 도구를 주지 않지만(보낼지는 코드가 정한다),
읽기 도구를 MCP 로 옮길 때 같은 변환을 쓴다.

## 동기 코드에서 부른다

MCP SDK 는 async 다. 노드는 sync 라 asyncio.run() 으로 감싼다. FastAPI 의 sync
엔드포인트는 스레드풀에서 돌아 그 스레드에 이벤트 루프가 없으므로 안전하다.
async 엔드포인트로 바꾸면 여기서 RuntimeError 가 난다 — 그때는 await 로 직접 부른다.
"""

import asyncio
import json
import os
from contextlib import asynccontextmanager

from .config import MCP_ARGS, MCP_COMMAND, MCP_TIMEOUT


class McpUnavailable(RuntimeError):
    """MCP 서버를 띄우거나 부르지 못했다. kind 는 common/errors.py FAILURE_KINDS 를 따른다."""

    def __init__(self, message: str, kind: str = "http_error"):
        super().__init__(message)
        self.kind = kind


def _server_env(base: dict) -> dict:
    """
    MCP 서버에게 넘길 환경변수.

    **stdio MCP 는 기본적으로 환경을 거의 안 넘긴다.** env 를 비워 두면 PATH · HOME
    정도만 간 안전한 기본값이 쓰여, 서버가 NOTIFY_SMTP_* 를 못 본다. 그러면 설정을
    제대로 넣어도 조용히 가짜 발송으로 떨어진다 — 아무 에러도 안 난다.

    그렇다고 os.environ 을 통째로 넘기지는 않는다. 메일 서버는 Luna 키를 쓸 일이
    없으므로 **NOTIFY_ 로 시작하는 것만** 넘긴다. 넘기는 자리가 적을수록 샐 자리도 적다.
    """
    return {**base, **{k: v for k, v in os.environ.items() if k.startswith("NOTIFY_")}}


@asynccontextmanager
async def _session():
    """itda-notify 서버를 띄우고 세션을 연다. 블록을 벗어나면 서버도 내린다."""
    try:
        from mcp import Client, StdioServerParameters
        from mcp.client.stdio import get_default_environment
    except ImportError as exc:  # pragma: no cover - 설치 안 된 환경
        raise McpUnavailable(
            "mcp 패키지가 없습니다. requirements.txt 를 설치하세요", "not_configured"
        ) from exc

    # 서버는 AI/ 를 작업 디렉터리로 본다 — mcp_servers 를 -m 으로 찾으려면 필요하다.
    params = StdioServerParameters(
        command=MCP_COMMAND,
        args=MCP_ARGS,
        cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        env=_server_env(get_default_environment()),
    )
    async with Client(params, raise_exceptions=False) as client:
        yield client


def to_luna_tools(mcp_tools) -> list[dict]:
    """
    MCP 도구 명세를 Luna 의 function tool 형식으로 바꾼다.

    두 형식은 거의 같다 — 이름이 다를 뿐이다.
        MCP:  {name, description, input_schema}
        Luna: {type: "function", name, description, parameters}

    ⚠️ 필드 이름이 mcp 1.x 에서는 inputSchema 였다. 2.x 에서 input_schema 로 바뀌었다.
    """
    return [
        {
            "type": "function",
            "name": tool.name,
            "description": tool.description or "",
            "parameters": tool.input_schema or {"type": "object", "properties": {}},
        }
        for tool in mcp_tools
    ]


def _result_dict(name: str, result) -> dict:
    """
    도구 결과를 dict 로 꺼낸다.

    두 자리를 본다. 반환 타입을 선언한 도구는 structured_content 가 채워지고,
    선언하지 않은 도구(지금의 send_email · check_delivery)는 JSON 글자가
    content 의 TextContent 로 온다. 둘 다 받아야 도구마다 다르게 깨지지 않는다.

    ⚠️ mcp 1.x 는 structuredContent · isError 였다. 2.x 에서 snake_case 로 바뀌었다.
    """
    structured = getattr(result, "structured_content", None)
    if isinstance(structured, dict):
        return structured

    for item in result.content or []:
        text = getattr(item, "text", None)
        if not text:
            continue
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            continue
        if isinstance(data, dict):
            return data

    raise McpUnavailable(f"도구 {name} 가 읽을 수 있는 결과를 주지 않았습니다")


async def _call(name: str, args: dict) -> dict:
    async with _session() as client:
        result = await client.call_tool(name, args)
        if result.is_error:
            text = " ".join(c.text for c in result.content if hasattr(c, "text"))
            raise McpUnavailable(f"도구 {name} 실패: {text[:200]}")
        return _result_dict(name, result)


def call_tool(name: str, args: dict, *, timeout: float = MCP_TIMEOUT) -> dict:
    """도구 하나를 부르고 결과를 돌려준다. 실패는 McpUnavailable 로 올린다."""
    try:
        return asyncio.run(asyncio.wait_for(_call(name, args), timeout))
    except McpUnavailable:
        raise
    except asyncio.TimeoutError as exc:
        raise McpUnavailable(f"도구 {name} 가 {timeout}초 안에 답하지 않았습니다", "timeout") from exc
    except RuntimeError as exc:
        # 이미 이벤트 루프가 도는 스레드에서 불렸다 (모듈 머리말).
        raise McpUnavailable(f"이벤트 루프 안에서 불렸습니다: {exc}", "http_error") from exc
    except Exception as exc:  # noqa: BLE001 - 서버를 못 띄운 경우까지 한 종류로 올린다
        raise McpUnavailable(f"MCP 호출 실패: {exc}", "http_error") from exc


def list_tools(*, timeout: float = MCP_TIMEOUT) -> list[dict]:
    """서버가 내놓는 도구 목록을 Luna 형식으로. 연결 확인과 문서화에 쓴다."""

    async def _run():
        async with _session() as client:
            return to_luna_tools((await client.list_tools()).tools)

    try:
        return asyncio.run(asyncio.wait_for(_run(), timeout))
    except Exception as exc:  # noqa: BLE001
        raise McpUnavailable(f"도구 목록을 받지 못했습니다: {exc}", "http_error") from exc
