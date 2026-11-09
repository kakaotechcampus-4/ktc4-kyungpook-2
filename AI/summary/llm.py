import json
import os
import re
from dataclasses import dataclass, field

import requests

from common.errors import LlmUnavailable  # noqa: F401

from common.luna import endpoint, json_format, not_configured
from common.luna import model as luna_model

from .config import LUNA_REASONING_EFFORT_ON_OVERFLOW, LUNA_TIMEOUT

AGENT = "summary"


class NoGroundedClaims(LlmUnavailable):

    reason = "no_grounded_claims"
    message = "근거가 남은 문장이 없음"


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


def ask_json(messages: list[dict], *, timeout: float = LUNA_TIMEOUT) -> LlmResult:
    url, key = _endpoint()
    payload = _post(url, key, messages, timeout)

    choice = (payload.get("choices") or [{}])[0] if isinstance(payload, dict) else {}
    content = (choice.get("message") or {}).get("content")
    retried = False
    if choice.get("finish_reason") == "length":
        print(f"[{AGENT}] 생각 한도 초과 → reasoning_effort={LUNA_REASONING_EFFORT_ON_OVERFLOW} 로 다시 부른다")
        payload = _post(url, key, messages, timeout,
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


def locate_quote(content: str, quote: str) -> dict | None:
    if not isinstance(quote, str) or not quote.strip():
        return None
    index = content.find(quote)
    if index < 0:
        return None
    return {"start": index, "end": index + len(quote)}
