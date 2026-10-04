# AI/summary/llm.py
"""
Luna 호출과 인용 대조.

여기서만 외부 API를 만진다. 노드는 이 모듈의 함수만 부른다 —
나중에 모델이 바뀌어도 고칠 곳이 한 군데다.
matching/llm.py · validation/llm.py 와 같은 모양이다.
"""

import json
import os
import re
from dataclasses import dataclass, field

import requests

from .config import LUNA_CHAT_PATH, LUNA_MODEL, LUNA_TIMEOUT


class LlmError(RuntimeError):
    """Luna 호출 또는 응답 해석이 실패했을 때."""


class LlmUnavailable(LlmError):
    """
    모델을 못 써서 요약을 만들지 못했을 때. API 는 503 을 돌려주고 BE 가 재시도한다.

    빈 요약을 200 으로 돌려주면 "그날 쓸 말이 없었다" 와 "모델이 죽었다" 가
    구별되지 않는다. 교사는 빈 글을 승인하게 되고 일지는 반영된 것처럼 닫힌다.

    #98 이 validation 에 같은 클래스를 넣는다. 머지되면 한 곳으로 합친다.
    """


@dataclass
class LlmResult:
    """모델 응답에서 우리가 쓰는 것만 담는다."""

    data: dict
    usage: dict = field(default_factory=dict)


def _endpoint() -> tuple[str, str]:
    url = os.environ.get("LUNA_API_URL")
    key = os.environ.get("LUNA_API_KEY")
    if not (url and key):
        raise LlmError("LUNA_API_URL / LUNA_API_KEY 가 설정되지 않았습니다")
    if not url.rstrip("/").endswith(LUNA_CHAT_PATH):
        url = url.rstrip("/") + LUNA_CHAT_PATH
    return url, key


def _extract_text(payload: dict) -> str:
    """응답에서 모델이 쓴 텍스트를 꺼낸다. Luna 는 OpenAI 호환이다."""
    try:
        content = payload["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        keys = list(payload)[:10] if isinstance(payload, dict) else type(payload)
        raise LlmError(f"응답 형태가 예상과 다릅니다. 최상위 키: {keys}") from exc

    if not isinstance(content, str):
        raise LlmError(f"content 가 문자열이 아닙니다: {type(content)}")
    return content


def _parse_json_block(text: str) -> dict:
    """
    모델이 준 텍스트에서 JSON 객체를 꺼낸다.

    형식을 강제해도 ```json 울타리를 두르거나 앞뒤에 문장을 붙이는 경우가 있어
    가장 바깥 중괄호 구간을 찾아서 파싱한다.
    """
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
    """
    Luna 에 물어보고 JSON 객체를 돌려받는다.

    response_format 으로 JSON 출력을 강제한다.
    temperature 는 보내지 않는다 — 이 모델은 기본값(1)만 허용한다.
    """
    url, key = _endpoint()

    try:
        response = requests.post(
            url,
            json={
                "model": LUNA_MODEL,
                "response_format": {"type": "json_object"},
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


def locate_quote(content: str, quote: str) -> dict | None:
    """
    인용 하나를 그 일지 본문 안 위치로 바꾼다. 못 찾으면 None 이다.

    matching/validation 의 spans_for_quotes 와 같은 일인데, 요약은 인용이
    **어느 일지의 것인지**까지 따져야 해서 한 건씩 받는다. 여러 일지를
    묶어 놓고 아무 데서나 찾으면, 모델이 A 일지 내용이라고 말한 문장을
    B 일지에서 찾아 통과시키게 된다.

    원문에 없는 인용(조사를 바꾸거나 다듬은 경우)은 버린다. 다듬은 인용은
    원문이 아니다 — CRITERIA.md §2 ①.
    """
    if not isinstance(quote, str) or not quote.strip():
        return None
    index = content.find(quote)
    if index < 0:
        return None
    return {"start": index, "end": index + len(quote)}
