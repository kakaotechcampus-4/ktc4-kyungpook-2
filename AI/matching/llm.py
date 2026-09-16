"""
Luna 호출과 응답 파싱.

여기서만 외부 API 를 만진다. 노드는 이 모듈의 함수만 부른다 —
나중에 모델이 바뀌어도 고칠 곳이 한 군데다.
"""

import json
import os
import re
from dataclasses import dataclass, field

import requests

from .config import LUNA_CHAT_PATH, LUNA_MODEL, LUNA_TIMEOUT


class LlmError(RuntimeError):
    """Luna 호출 또는 응답 해석이 실패했을 때."""


@dataclass
class LlmResult:
    """모델 응답에서 우리가 쓰는 것만 담는다."""

    data: dict
    #: 토큰 사용량. cached_tokens 로 프롬프트 캐싱이 실제로 먹는지 확인한다.
    usage: dict = field(default_factory=dict)


def _endpoint() -> tuple[str, str]:
    url = os.environ.get("LUNA_API_URL")
    key = os.environ.get("LUNA_API_KEY")
    if not (url and key):
        raise LlmError("LUNA_API_URL / LUNA_API_KEY 가 설정되지 않았습니다")
    # .env 에는 base 만 둔다. 경로가 이미 붙어 있으면 그대로 쓴다.
    if not url.rstrip("/").endswith(LUNA_CHAT_PATH):
        url = url.rstrip("/") + LUNA_CHAT_PATH
    return url, key


def _extract_text(payload: dict) -> str:
    """
    응답에서 모델이 쓴 텍스트를 꺼낸다.

    Luna 는 OpenAI 호환이라 choices[0].message.content 하나만 보면 된다.
    (2026-09-15 실제 응답으로 확인. model=gpt-5.6-luna)
    """
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

    response_format 으로 JSON 출력을 강제한다. 자유 텍스트를 파싱하면
    형식이 조금만 흔들려도 깨지고, 그 재시도가 기획서 리스크 #4(Agent 누적 실패)를
    키운다.

    재시도는 여기서 하지 않는다. 예외를 LlmError 로 바꿔 노드가 한 곳에서
    처리하게 한다 — 노드마다 재시도를 넣으면 지연이 곱해진다.

    temperature 는 보내지 않는다. 이 모델은 기본값(1)만 허용한다.
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


def spans_for_quotes(content: str, quotes: list) -> list[dict]:
    """
    모델이 인용한 문자열을 본문 안 위치로 바꾼다.

    모델에게 start / end 숫자를 직접 요구하지 않는다 — 글자 수를 세지 못해서
    거의 틀린다. 인용문만 받고 위치는 코드가 찾는다.
    원문에 없는 인용(조사를 바꾸거나 다듬은 경우)은 버린다.
    """
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
