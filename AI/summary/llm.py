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

#: 503 으로 나가는 예외. 모든 에이전트가 **같은 클래스**를 쓴다 — 따로 두면
#: main.py 에서 이름이 덮여 핸들러가 한쪽에만 걸린다 (common/errors.py 참고).
#: 빈 요약을 200 으로 주면 "쓸 말이 없었다" 와 "모델이 죽었다" 가 구별되지 않는다.
#: 여기서 다시 내보내므로 from summary.llm import LlmUnavailable 이 그대로 된다.
from common.errors import LlmUnavailable  # noqa: F401

from common.luna import endpoint, json_format, not_configured
from common.luna import model as luna_model

from .config import LUNA_REASONING_EFFORT_ON_OVERFLOW, LUNA_TIMEOUT

#: 로그에 찍는 이름
AGENT = "summary"


#: 재료가 있는데 근거 있는 문장이 하나도 안 남았을 때. LlmUnavailable 을 물려받아
#: 같은 핸들러가 503 으로 바꾼다 — BE 는 5xx 면 재시도하고, 4xx 면 그 건을 FAILED
#: 로 끝낸다. 이것은 버그가 아니라 "이번 호출에서 모델이 근거를 못 댔다" 이므로
#: 다시 부르면 될 수 있다 (#146).
class NoGroundedClaims(LlmUnavailable):
    """
    근거가 남은 문장이 하나도 없다.

    BE 는 이 reason 을 보고 **그 묶음만** 되돌려 다음 묶음으로 넘어간다.
    같은 묶음이 두 번 넘게 여기 걸리면 FAILED 로 끝낸다 (#146 · #148).
    """

    reason = "no_grounded_claims"
    message = "근거가 남은 문장이 없음"


class LlmError(RuntimeError):
    """Luna 호출 또는 응답 해석이 실패했을 때.

    kind 는 실패 종류다 (common/errors.py FAILURE_KINDS). 503 기록에 실려
    "빈 응답" 과 "타임아웃" 을 따로 셀 수 있게 한다.
    """

    def __init__(self, message: str, kind: str = "unknown"):
        super().__init__(message)
        self.kind = kind




@dataclass
class LlmResult:
    """모델 응답에서 우리가 쓰는 것만 담는다."""

    data: dict
    usage: dict = field(default_factory=dict)


def _endpoint() -> tuple[str, str]:
    # .env 의 SUMMARY_API_URL · API_KEY (common/luna.py endpoint)
    found = endpoint(AGENT)
    if not found:
        raise LlmError(not_configured(AGENT), "not_configured")
    return found


def _extract_text(payload: dict) -> str:
    """응답에서 모델이 쓴 텍스트를 꺼낸다. Luna 는 OpenAI 호환이다."""
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
        raise LlmError(f"응답에서 JSON 을 찾지 못했습니다: {text[:200]}", "bad_json")
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise LlmError(f"JSON 파싱 실패: {text[:200]}", "bad_json") from exc


def ask_json(messages: list[dict], *, timeout: float = LUNA_TIMEOUT) -> LlmResult:
    """
    Luna 에 물어보고 JSON 객체를 돌려받는다.

    response_format 으로 JSON 출력을 강제한다.
    temperature 는 보내지 않는다 — 이 모델은 기본값(1)만 허용한다.
    """
    url, key = _endpoint()
    payload = _post(url, key, messages, timeout)

    # Luna 프록시는 출력(생각 + 답)을 2000 토큰까지만 준다. 입력이 길면 생각에 다 써서
    # 답이 빈 채로 온다 (finish_reason=length). 그때만 생각을 줄여 한 번 더 부른다.
    # 처음부터 줄이면 보통 입력의 품질까지 떨어진다 (실데이터 v3, 2026-10-30).
    choice = (payload.get("choices") or [{}])[0] if isinstance(payload, dict) else {}
    content = (choice.get("message") or {}).get("content")
    retried = False
    # 답이 반쯤 온 채로 잘리는 모델도 있다 (claude-opus-5-5, 2026-11-09: 2000 토큰에서 JSON 이 끊김).
    # 빈 답만 보면 그 경우를 놓치므로, 잘렸으면(length) 답이 있어도 다시 부른다.
    if choice.get("finish_reason") == "length":
        print(f"[{AGENT}] 생각 한도 초과 → reasoning_effort={LUNA_REASONING_EFFORT_ON_OVERFLOW} 로 다시 부른다")
        payload = _post(url, key, messages, timeout,
                        reasoning_effort=LUNA_REASONING_EFFORT_ON_OVERFLOW)
        retried = True

    usage = dict((payload.get("usage") if isinstance(payload, dict) else None) or {})
    usage["reasoning_retry"] = retried
    data = _parse_json_block(_extract_text(payload))
    # Luna 가 가끔 {} 만 돌려준다 (2026-10-28 실데이터 v1). 형식은 맞지만 내용이 없다.
    if not data:
        raise LlmError("빈 응답 ({})", "empty_response")
    return LlmResult(data=data, usage=usage)


def _post(url: str, key: str, messages: list[dict], timeout: float,
          reasoning_effort: str | None = None) -> dict:
    """Luna 를 한 번 부른다. reasoning_effort 를 주지 않으면 모델 기본값으로 생각한다."""
    body = {
        "model": luna_model(AGENT),
        **json_format(AGENT),  # 기본 JSON 강제, SUMMARY_JSON_MODE=off 면 뺀다
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
