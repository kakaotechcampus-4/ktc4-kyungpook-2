import asyncio
import json
import os
from contextlib import asynccontextmanager

from .config import MCP_ARGS, MCP_COMMAND, MCP_TIMEOUT


class McpUnavailable(RuntimeError):

    def __init__(self, message: str, kind: str = "http_error"):
        super().__init__(message)
        self.kind = kind


def _server_env(base: dict) -> dict:
    return {**base, **{k: v for k, v in os.environ.items() if k.startswith("NOTIFY_")}}


@asynccontextmanager
async def _session():
    try:
        from mcp import Client, StdioServerParameters
        from mcp.client.stdio import get_default_environment
    except ImportError as exc:  # pragma: no cover - 설치 안 된 환경
        raise McpUnavailable(
            "mcp 패키지가 없습니다. requirements.txt 를 설치하세요", "not_configured"
        ) from exc

    params = StdioServerParameters(
        command=MCP_COMMAND,
        args=MCP_ARGS,
        cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        env=_server_env(get_default_environment()),
    )
    async with Client(params, raise_exceptions=False) as client:
        yield client


def to_luna_tools(mcp_tools) -> list[dict]:
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
    try:
        return asyncio.run(asyncio.wait_for(_call(name, args), timeout))
    except McpUnavailable:
        raise
    except asyncio.TimeoutError as exc:
        raise McpUnavailable(f"도구 {name} 가 {timeout}초 안에 답하지 않았습니다", "timeout") from exc
    except RuntimeError as exc:
        raise McpUnavailable(f"이벤트 루프 안에서 불렸습니다: {exc}", "http_error") from exc
    except Exception as exc:  # noqa: BLE001 - 서버를 못 띄운 경우까지 한 종류로 올린다
        raise McpUnavailable(f"MCP 호출 실패: {exc}", "http_error") from exc


def list_tools(*, timeout: float = MCP_TIMEOUT) -> list[dict]:

    async def _run():
        async with _session() as client:
            return to_luna_tools((await client.list_tools()).tools)

    try:
        return asyncio.run(asyncio.wait_for(_run(), timeout))
    except Exception as exc:  # noqa: BLE001
        raise McpUnavailable(f"도구 목록을 받지 못했습니다: {exc}", "http_error") from exc
