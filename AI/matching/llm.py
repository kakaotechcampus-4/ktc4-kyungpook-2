"""
Luna 호출과 응답 파싱.

여기서만 외부 API 를 만진다. 노드는 이 모듈의 함수만 부른다 —
나중에 모델이 바뀌어도 고칠 곳이 한 군데다.
"""

import json
import os
import re

import requests


class LlmError(RuntimeError):
    """Luna 호출 또는 응답 해석이 실패했을 때."""


def _endpoint() -> tuple[str, str]:
    url = os.environ.get("LUNA_API_URL")
    key = os.environ.get("LUNA_API_KEY")
    if not (url and key):
        raise LlmError("LUNA_API_URL / LUNA_API_KEY 가 설정되지 않았습니다")
    return url, key


def _extract_text(payload) -> str:
    """
    응답 본문에서 모델이 쓴 텍스트만 꺼낸다.

    Luna 의 정확한 응답 스키마를 아직 확인하지 못해서, 흔한 형태를 순서대로 시도한다.
    실제 형태를 확인하면 이 함수만 그 형태로 줄이면 된다.
    """
    if isinstance(payload, str):
        return payload

    if isinstance(payload, dict):
        # OpenAI 호환
        choices = payload.get("choices")
        if isinstance(choices, list) and choices:
            message = choices[0].get("message") or {}
            if isinstance(message.get("content"), str):
                return message["content"]
            if isinstance(choices[0].get("text"), str):
                return choices[0]["text"]

        # Anthropic 계열
        content = payload.get("content")
        if isinstance(content, list) and content:
            first = content[0]
            if isinstance(first, dict) and isinstance(first.get("text"), str):
                return first["text"]
        if isinstance(content, str):
            return content

        # 단순 형태
        for key in ("text", "output", "result", "response", "answer", "message"):
            value = payload.get(key)
            if isinstance(value, str):
                return value

    raise LlmError(
        f"응답에서 텍스트를 찾지 못했습니다. 최상위 키: {list(payload)[:10] if isinstance(payload, dict) else type(payload)}"
    )


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


def ask_json(messages: list[dict], *, timeout: float = 60.0) -> dict:
    """
    Luna 에 물어보고 JSON 객체를 돌려받는다.

    재시도는 여기서 하지 않는다. requests 가 올린 예외를 LlmError 로 바꿔서
    노드가 한 곳에서 처리하게 한다 — 노드마다 재시도를 넣으면 지연이 곱해지고,
    기획서 리스크 #4(Agent 누적 실패)를 키운다.
    """
    url, key = _endpoint()

    try:
        response = requests.post(
            url,
            json={"messages": messages},
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

    return _parse_json_block(_extract_text(response.json()))


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
