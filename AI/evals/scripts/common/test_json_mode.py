# AI/evals/scripts/common/test_json_mode.py
"""
LUNA_JSON_MODE 로 response_format(JSON 강제)을 켜고 끄는지 네 에이전트 모두 검사한다. Luna 를 부르지 않는다.

    python evals/scripts/common/test_json_mode.py

기본은 켜짐 — 루나와 서버는 지금처럼 JSON 을 강제한다. 다른 모델 비교에서 그 모델이
response_format 을 받지 않으면(claude-opus-5-5, 400) LUNA_JSON_MODE=off 로 끈다.
끈 상태에서도 응답 앞뒤에 말이 붙은 JSON 을 골라내는지 같이 본다.
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from insight import llm as insight_llm  # noqa: E402
from matching import llm as matching_llm  # noqa: E402
from summary import llm as summary_llm  # noqa: E402
from validation import llm as validation_llm  # noqa: E402

os.environ.setdefault("LUNA_API_URL", "http://luna.test")
os.environ.setdefault("LUNA_API_KEY", "test-key")

MODULES = {"매칭": matching_llm, "검증": validation_llm, "요약": summary_llm, "인사이트": insight_llm}


class FakeResponse:
    def __init__(self, body):
        self._body = body

    def raise_for_status(self):
        pass

    def json(self):
        return self._body


def send(module, content='{"ok": 1}'):
    """한 번 부르고 (보낸 body, 받은 data) 를 돌려준다."""
    sent = []

    def post(url, json, **kw):
        sent.append(json)
        return FakeResponse({"choices": [{"message": {"content": content}, "finish_reason": "stop"}], "usage": {}})

    original = module.requests.post
    module.requests.post = post
    try:
        result = module.ask_json([{"role": "user", "content": "x"}])
    finally:
        module.requests.post = original
    return sent[0], result.data


def with_mode(value):
    if value is None:
        os.environ.pop("LUNA_JSON_MODE", None)
    else:
        os.environ["LUNA_JSON_MODE"] = value


def main() -> int:
    fails = []
    for name, module in MODULES.items():
        with_mode(None)
        body, _ = send(module)
        if body.get("response_format") != {"type": "json_object"}:
            fails.append(f"{name}: 기본값인데 JSON 강제가 없다 {body}")

        with_mode("off")
        body, data = send(module, content='다음과 같습니다.\n```json\n{"ok": 1}\n```')
        if "response_format" in body:
            fails.append(f"{name}: off 인데 response_format 을 보냈다")
        if data != {"ok": 1}:
            fails.append(f"{name}: off 에서 앞뒤 말이 붙은 JSON 을 못 골라냄 {data}")

        with_mode("on")
        body, _ = send(module)
        if "response_format" not in body:
            fails.append(f"{name}: on 인데 JSON 강제가 없다")
    with_mode(None)

    for f in fails:
        print("FAIL", f)
    print(f"\n{len(MODULES) * 3 - len(fails)}/{len(MODULES) * 3} 통과" if not fails else f"\n실패 {len(fails)}건")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
