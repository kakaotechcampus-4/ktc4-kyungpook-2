"""
Luna 호출과 응답 파싱.

여기서만 외부 API를 만진다. 노드는 이 모듈의 함수만 부른다 —
나중에 모델이 바뀌어도 고칠 곳이 한 군데다.
"""

import json
import os
import re
from dataclasses import dataclass, field

import time

import requests

#: 503 으로 나가는 예외. 모든 에이전트가 **같은 클래스**를 쓴다 — 따로 두면
#: main.py 에서 이름이 덮여 핸들러가 한쪽에만 걸린다 (common/errors.py 참고).
#: LLM 판단 없이는 판정을 낼 수 없다.
#: 여기서 다시 내보내므로 from insight.llm import LlmUnavailable 이 그대로 된다.
from common.errors import LlmUnavailable  # noqa: F401

from common.luna import endpoint, json_format, not_configured
from common.luna import model as luna_model

from .config import LUNA_CHAT_PATH, LUNA_REASONING_EFFORT_ON_OVERFLOW, LUNA_TIMEOUT

#: 로그에 찍는 이름
AGENT = "insight"


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
    # .env 의 INSIGHT_API_URL · API_KEY (common/luna.py endpoint)
    found = endpoint(AGENT)
    if not found:
        raise LlmError(not_configured(AGENT), "not_configured")
    return found


def is_configured() -> bool:
    """
    Luna 를 부를 설정(URL·키)이 있는지. /health 가 쓴다.
    실제로 호출하지는 않는다 — 헬스체크마다 모델을 부르면 비용과 지연이 생긴다.
    모델이 바뀌면 이 함수와 _endpoint 만 고치면 된다.
    """
    try:
        _endpoint()
    except LlmError:
        return False
    return True


def _extract_text(payload: dict) -> str:
    """
    응답에서 모델이 쓴 텍스트를 꺼낸다.
    Luna는 OpenAI 호환이라 choices[0].message.content 하나만 보면 된다.
    """
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


#: 마감까지 이보다 적게 남으면 부르지 않는다 — 부르자마자 시간 초과가 날 호출이다.
MIN_CALL_SECONDS = 5.0


def _budget(timeout: float, deadline: float | None) -> float:
    """이번 호출의 제한시간 = min(timeout, 마감까지 남은 시간). 모자라면 LlmError(deadline)."""
    if deadline is None:
        return timeout
    left = deadline - time.monotonic()
    if left < MIN_CALL_SECONDS:
        raise LlmError(f"시간 상한 (남은 {left:.0f}초)", "deadline")
    return min(timeout, left)


def ask_json(messages: list[dict], *, timeout: float = LUNA_TIMEOUT,
             deadline: float | None = None) -> LlmResult:
    """
    Luna에 물어보고 JSON 객체를 돌려받는다.
    response_format으로 JSON 출력을 강제한다.
    temperature는 보내지 않는다 — 이 모델은 기본값(1)만 허용한다.
    """
    url, key = _endpoint()
    payload = _post(url, key, messages, _budget(timeout, deadline))

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
        payload = _post(url, key, messages, _budget(timeout, deadline),
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
        **json_format(AGENT),  # 기본 JSON 강제, INSIGHT_JSON_MODE=off 면 뺀다
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
    """
    모델이 인용한 문자열을 본문 안 위치로 바꾼다.
    모델에게 start/end 숫자를 직접 요구하지 않는다 — 글자 수를 세지 못해서
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


# ── 도구 사용 (딥다이브 3) ──────────────────────────────────────

#: Luna 는 /v1/chat/completions 에서 생각(reasoning)과 도구(tools)를 같이 받지 않는다 (11/06, 400).
#: "/v1/responses 를 쓰거나 reasoning_effort 를 none 으로" 라고 답한다. 생각을 끄면 품질이
#: 떨어지므로(실데이터 v3) 도구가 필요한 호출만 /v1/responses 로 부른다.
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
        # 서버가 돌려준 에러 문구를 남긴다 — 없으면 원인이 http_error 로만 보인다 (11/09 sonnet 400)
        detail = getattr(getattr(exc, "response", None), "text", "") or ""
        raise LlmError(f"Luna 호출 실패: {exc} {detail[:300]}".strip(), "http_error") from exc
    try:
        return response.json()
    except ValueError as exc:
        raise LlmError(f"응답 본문이 JSON 이 아닙니다: {response.text[:200]}", "bad_json") from exc


def ask_with_tools(instructions: str, user_input: str, tools: list[dict], run_tool,
                   *, max_calls: int = 2, timeout: float = LUNA_TIMEOUT,
                   deadline: float | None = None) -> LlmResult:
    """
    도구를 쓸 수 있게 Luna 를 부르고, 마지막에 JSON 객체를 돌려받는다.

    모델이 도구를 부르면 run_tool(이름, 인자) 결과를 돌려주고 다시 부른다. 도구 호출은
    max_calls 번까지 — 넘으면 tool_choice="none" 으로 도구 없이 답하게 한다.
    usage["tool_calls"] 에 실제로 실행한 도구 호출 수를 담는다.
    """
    chat_url, key = _endpoint()
    url = chat_url[: -len(LUNA_CHAT_PATH)] + RESPONSES_PATH
    body = {"instructions": instructions, "input": user_input, "tools": tools}
    executed = 0
    usage: dict = {}
    #: 지금까지 오간 대화. 프록시가 previous_response_id 를 거절하면 이것을 통째로 다시 보낸다.
    #: claude 계열은 엘리스 프록시에서 "Unsupported parameter(s) ... previous_response_id" 로 400 (11/09).
    #: 루나는 previous_response_id 로 3/3 을 확인했으므로 거절될 때만 바꾼다.
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
