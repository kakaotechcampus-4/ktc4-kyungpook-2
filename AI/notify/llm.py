# AI/notify/llm.py
"""
Luna 호출과 응답 파싱. 여기서만 모델을 만진다.

구조는 insight/llm.py 와 같고 도구 호출이 없다 — **메일을 보낼지 말지는 모델이
정하지 않기 때문이다.** 모델은 글만 쓰고, 보낼지는 check 노드가 정하고,
실제 호출은 send 노드가 MCP 로 한다 (graph.py).
"""

import json
import os
import re
from dataclasses import dataclass, field

import requests

#: 503 으로 나가는 예외. 모든 에이전트가 같은 클래스를 쓴다 — 따로 두면 main.py 에서
#: 이름이 덮여 핸들러가 한쪽에만 걸린다 (common/errors.py).
from common.errors import LlmUnavailable  # noqa: F401
from common.luna import model as luna_model

from .config import LUNA_CHAT_PATH, LUNA_REASONING_EFFORT_ON_OVERFLOW, LUNA_TIMEOUT

AGENT = "notify"


class LlmError(RuntimeError):
    """Luna 호출 또는 응답 해석이 실패했을 때. kind 는 common/errors.py FAILURE_KINDS."""

    def __init__(self, message: str, kind: str = "unknown"):
        super().__init__(message)
        self.kind = kind


class NotifyDeliveryFailed(LlmUnavailable):
    """
    메일을 보내려다 실패했다 (MCP 호출 실패·메일 서버 장애).

    **근거 미달과 반드시 나눈다.** 근거 미달은 200 blocked 로 내려가 교사 확인 큐에 남고,
    이것은 503 이라 BE 가 재시도한다. 둘을 섞으면 교사가 고칠 수 없는 것을 큐에서 보거나,
    고쳐야 할 것이 조용히 재시도만 돌다 끝난다.
    """

    reason = "delivery_failed"
    message = "메일 발송 실패"


@dataclass
class LlmResult:
    data: dict
    usage: dict = field(default_factory=dict)


def _endpoint() -> tuple[str, str]:
    url = os.environ.get("LUNA_API_URL")
    key = os.environ.get("LUNA_API_KEY")
    if not (url and key):
        raise LlmError("LUNA_API_URL / LUNA_API_KEY 가 설정되지 않았습니다", "not_configured")
    if not url.rstrip("/").endswith(LUNA_CHAT_PATH):
        url = url.rstrip("/") + LUNA_CHAT_PATH
    return url, key


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
    """형식을 강제해도 ```json 울타리를 두르는 경우가 있어 바깥 중괄호 구간을 찾는다."""
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


def _post(url: str, key: str, messages: list[dict], timeout: float,
          reasoning_effort: str | None = None) -> dict:
    body = {"model": luna_model(), "messages": messages,
            "response_format": {"type": "json_object"}}
    if reasoning_effort:
        body["reasoning_effort"] = reasoning_effort
    try:
        res = requests.post(url, headers={"Authorization": f"Bearer {key}"},
                            json=body, timeout=timeout)
    except requests.Timeout as exc:
        raise LlmError(f"{timeout}초 안에 답이 없습니다", "timeout") from exc
    except requests.RequestException as exc:
        raise LlmError(f"호출 실패: {exc}", "http_error") from exc
    if res.status_code >= 400:
        raise LlmError(f"HTTP {res.status_code}: {res.text[:200]}", "http_error")
    try:
        return res.json()
    except ValueError as exc:
        raise LlmError(f"JSON 이 아닌 응답: {res.text[:200]}", "bad_json") from exc


def ask_json(messages: list[dict], *, timeout: float = LUNA_TIMEOUT) -> LlmResult:
    """
    Luna 에 물어보고 JSON 객체를 돌려받는다.

    생각에 토큰을 다 써서 답이 빈 채로 오면(finish_reason=length) 한 번만 생각을
    줄여 다시 부른다 — 요약과 같은 처리다. 처음부터 줄이면 글의 품질이 떨어진다.
    """
    url, key = _endpoint()
    payload = _post(url, key, messages, timeout)

    choice = (payload.get("choices") or [{}])[0] if isinstance(payload, dict) else {}
    content = (choice.get("message") or {}).get("content")
    if choice.get("finish_reason") == "length" and not (content or "").strip():
        print(f"[{AGENT}] 생각 한도 초과 → reasoning_effort={LUNA_REASONING_EFFORT_ON_OVERFLOW} 로 다시 부른다")
        payload = _post(url, key, messages, timeout, LUNA_REASONING_EFFORT_ON_OVERFLOW)

    data = _parse_json_block(_extract_text(payload))
    usage = {k: v for k, v in (payload.get("usage") or {}).items() if isinstance(v, int)}
    return LlmResult(data=data, usage=usage)
