import json
import os
import re
from dataclasses import dataclass, field

import requests

from common.errors import LlmUnavailable  # noqa: F401

from common.luna import endpoint, json_format, not_configured
from common.luna import model as luna_model

from .config import LUNA_TIMEOUT

AGENT = "validation"


class LlmError(RuntimeError):
    pass




@dataclass
class LlmResult:

    data: dict
    usage: dict = field(default_factory=dict)


def _endpoint() -> tuple[str, str]:
    found = endpoint(AGENT)
    if not found:
        raise LlmError(not_configured(AGENT))
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
        raise LlmError(f"응답 형태가 예상과 다릅니다. 최상위 키: {keys}") from exc

    if not isinstance(content, str):
        raise LlmError(f"content 가 문자열이 아닙니다: {type(content)}")
    return content


def _parse_json_block(text: str) -> dict:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise LlmError(f"응답에서 JSON 을 찾지 못했습니다: {text[:200]}")
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise LlmError(f"JSON 파싱 실패: {text[:200]}") from exc


def ask_json(messages: list[dict], *, timeout: float = LUNA_TIMEOUT) -> LlmResult:
    url, key = _endpoint()

    try:
        response = requests.post(
            url,
            json={
                "model": luna_model(AGENT),
                **json_format(AGENT),
                "messages": messages,
            },
            headers={
                "accept": "application/json",
                "content-type": "application/json",
                "Authorization": f"Bearer {key}",
            },
            timeout=timeout,
        )
        response.raise_for_status()
    except requests.RequestException as exc:
        raise LlmError(f"Luna 호출 실패: {exc}") from exc

    payload = response.json()
    return LlmResult(
        data=_parse_json_block(_extract_text(payload)),
        usage=payload.get("usage") or {},
    )


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