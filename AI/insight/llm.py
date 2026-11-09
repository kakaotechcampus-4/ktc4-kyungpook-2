import json
import os
import re
from dataclasses import dataclass, field

import time

import requests

from common.errors import LlmUnavailable  # noqa: F401

from common.luna import endpoint, json_format, not_configured
from common.luna import model as luna_model

from .config import LUNA_CHAT_PATH, LUNA_REASONING_EFFORT_ON_OVERFLOW, LUNA_TIMEOUT

AGENT = "insight"


class LlmError(RuntimeError):

    def __init__(self, message: str, kind: str = "unknown"):
        super().__init__(message)
        self.kind = kind




@dataclass
class LlmResult:

    data: dict
    usage: dict = field(default_factory=dict)


def _endpoint() -> tuple[str, str]:
    found = endpoint(AGENT)
    if not found:
        raise LlmError(not_configured(AGENT), "not_configured")
    return found


def is_configured() -> bool:
    try:
        _endpoint()
    except LlmError:
        return False
    return True


def _extract_text(payload: dict) -> str:
    try:
        content = payload["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        keys = list(payload)[:10] if isinstance(payload, dict) else type(payload)
        raise LlmError(f"응답 형태가 예상과 다릅니다. 최상위 키: {keys}", "bad_response") from exc

    if not isinstance(content, str):
        raise LlmError(f"content 가 문자열이 아닙니다: {type(content)}", "bad_response")
    if not content.strip():
        raise LlmError("빈 응답 (content 가 비어 있음)", "empty_response")
    return content


def _parse_json_block(text: str) -> dict:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise LlmError(f"응답에서 JSON 을 찾지 못했습니다: {text[:200]}", "bad_json")
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise LlmError(f"JSON 파싱 실패: {text[:200]}", "bad_json") from exc


MIN_CALL_SECONDS = 5.0


def _budget(timeout: float, deadline: float | None) -> float:
    if deadline is None:
        return timeout
    left = deadline - time.monotonic()
    if left < MIN_CALL_SECONDS:
        raise LlmError(f"시간 상한 (남은 {left:.0f}초)", "deadline")
    return min(timeout, left)


def ask_json(messages: list[dict], *, timeout: float = LUNA_TIMEOUT,
             deadline: float | None = None) -> LlmResult:
    url, key = _endpoint()
    payload = _post(url, key, messages, _budget(timeout, deadline))

    choice = (payload.get("choices") or [{}])[0] if isinstance(payload, dict) else {}
    content = (choice.get("message") or {}).get("content")
    retried = False
    if choice.get("finish_reason") == "length":
        print(f"[{AGENT}] 생각 한도 초과 → reasoning_effort={LUNA_REASONING_EFFORT_ON_OVERFLOW} 로 다시 부른다")
        payload = _post(url, key, messages, _budget(timeout, deadline),
                        reasoning_effort=LUNA_REASONING_EFFORT_ON_OVERFLOW)
        retried = True

    usage = dict((payload.get("usage") if isinstance(payload, dict) else None) or {})
    usage["reasoning_retry"] = retried
    data = _parse_json_block(_extract_text(payload))
    if not data:
        raise LlmError("빈 응답 ({})", "empty_response")
    return LlmResult(data=data, usage=usage)


def _post(url: str, key: str, messages: list[dict], timeout: float,
          reasoning_effort: str | None = None) -> dict:
    body = {
        "model": luna_model(AGENT),
        **json_format(AGENT),
        "messages": messages,
    }
    if reasoning_effort:
        body["reasoning_effort"] = reasoning_effort
    try:
        response = requests.post(
            url,
            json=body,
            headers={
                "accept": "application/json",
                "content-type": "application/json",
                "Authorization": f"Bearer {key}",
            },
            timeout=timeout,
        )
        response.raise_for_status()
    except requests.Timeout as exc:
        raise LlmError(f"Luna 제한시간 초과 ({timeout}s): {exc}", "timeout") from exc
    except requests.RequestException as exc:
        raise LlmError(f"Luna 호출 실패: {exc}", "http_error") from exc
    try:
        return response.json()
    except ValueError as exc:
        raise LlmError(f"응답 본문이 JSON 이 아닙니다: {response.text[:200]}", "bad_json") from exc


def spans_for_quotes(content: str, quotes: list) -> list[dict]:
    spans: list[dict] = []
    for quote in quotes or []:
        if not isinstance(quote, str) or not quote.strip():
            continue
        index = content.find(quote)
        if index < 0:
            continue
        span = {"start": index, "end": index + len(quote)}
        if span not in spans:
            spans.append(span)
    return spans


RESPONSES_PATH = "/v1/responses"


def _responses(url: str, key: str, body: dict, timeout: float) -> dict:
    try:
        response = requests.post(
            url, json={"model": luna_model(AGENT), **body},
            headers={"content-type": "application/json", "Authorization": f"Bearer {key}"},
            timeout=timeout,
        )
        response.raise_for_status()
    except requests.Timeout as exc:
        raise LlmError(f"Luna 제한시간 초과 ({timeout}s): {exc}", "timeout") from exc
    except requests.RequestException as exc:
        detail = getattr(getattr(exc, "response", None), "text", "") or ""
        raise LlmError(f"Luna 호출 실패: {exc} {detail[:300]}".strip(), "http_error") from exc
    try:
        return response.json()
    except ValueError as exc:
        raise LlmError(f"응답 본문이 JSON 이 아닙니다: {response.text[:200]}", "bad_json") from exc


def ask_with_tools(instructions: str, user_input: str, tools: list[dict], run_tool,
                   *, max_calls: int = 2, timeout: float = LUNA_TIMEOUT,
                   deadline: float | None = None) -> LlmResult:
    chat_url, key = _endpoint()
    url = chat_url[: -len(LUNA_CHAT_PATH)] + RESPONSES_PATH
    body = {"instructions": instructions, "input": user_input, "tools": tools}
    executed = 0
    usage: dict = {}
    history: list[dict] = [{"role": "user", "content": user_input}]
    replay = False

    for _ in range(max_calls + 1):
        try:
            payload = _responses(url, key, body, _budget(timeout, deadline))
        except LlmError as exc:
            if replay or "previous_response_id" not in body or "previous_response_id" not in str(exc):
                raise
            replay = True
            body = {k: v for k, v in body.items() if k not in ("previous_response_id", "input")}
            body["input"] = list(history)
            payload = _responses(url, key, body, _budget(timeout, deadline))
        for k, v in (payload.get("usage") or {}).items():
            if isinstance(v, int):
                usage[k] = usage.get(k, 0) + v
        if payload.get("status") == "incomplete":
            raise LlmError(f"응답이 잘림: {payload.get('incomplete_details')}", "empty_response")

        output = payload.get("output") or []
        calls = [it for it in output if it.get("type") == "function_call"]
        if not calls:
            text = "".join(
                c.get("text", "") for it in output if it.get("type") == "message"
                for c in it.get("content") or []
            )
            if not text.strip():
                raise LlmError("빈 응답 (message 가 없음)", "empty_response")
            usage["tool_calls"] = executed
            data = _parse_json_block(text)
            if not data:
                raise LlmError("빈 응답 ({})", "empty_response")
            return LlmResult(data=data, usage=usage)

        history += [{k: call.get(k) for k in ("type", "call_id", "name", "arguments")} for call in calls]
        results = []
        for call in calls:
            try:
                args = json.loads(call.get("arguments") or "{}")
            except json.JSONDecodeError:
                args = {}
            executed += 1
            out = run_tool(call.get("name"), args if isinstance(args, dict) else {})
            results.append({"type": "function_call_output", "call_id": call.get("call_id"),
                            "output": json.dumps(out, ensure_ascii=False)})
        history += results
        body = {"instructions": instructions, "tools": tools}
        if replay:
            body["input"] = list(history)
        else:
            body.update(previous_response_id=payload.get("id"), input=results)
        if executed >= max_calls:
            body["tool_choice"] = "none"

    raise LlmError("도구 호출 한도를 넘겼는데도 답이 없음", "empty_response")
