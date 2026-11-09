import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from insight import llm as insight_llm  # noqa: E402
from summary import llm as summary_llm  # noqa: E402

for _agent in ("MATCHING", "VALIDATION", "SUMMARY", "INSIGHT"):
    os.environ.setdefault(f"{_agent}_API_URL", "http://luna.test")
os.environ.setdefault("API_KEY", "test-key")


class FakeResponse:
    def __init__(self, body):
        self._body = body

    def raise_for_status(self):
        pass

    def json(self):
        return self._body


def _reply(content, finish):
    return {"choices": [{"message": {"content": content}, "finish_reason": finish}], "usage": {}}


def _fake(replies):
    sent = []

    def post(url, json, **kw):
        sent.append(json)
        return FakeResponse(replies[len(sent) - 1])
    return post, sent


def run(module, replies):
    original = module.requests.post
    post, sent = _fake(replies)
    module.requests.post = post
    try:
        try:
            result = module.ask_json([{"role": "user", "content": "x"}])
        except module.LlmError:
            result = None
        return result, sent
    finally:
        module.requests.post = original


def check(module) -> list[str]:
    fails = []

    result, sent = run(module, [_reply('{"ok": 1}', "stop")])
    if len(sent) != 1 or "reasoning_effort" in sent[0] or result.usage["reasoning_retry"]:
        fails.append(f"보통 응답: 호출 {len(sent)}번, body={sent}")

    result, sent = run(module, [_reply("", "length"), _reply('{"ok": 1}', "stop")])
    if len(sent) != 2 or sent[1].get("reasoning_effort") != "low" or not result or result.data != {"ok": 1}:
        fails.append(f"한도 초과: 호출 {len(sent)}번, 두 번째 body={sent[1:]}")
    elif not result.usage["reasoning_retry"]:
        fails.append("한도 초과: usage 에 reasoning_retry 표시가 없다")

    result, sent = run(module, [_reply('{"claims": [{"text": "잘린', "length"), _reply('{"ok": 1}', "stop")])
    if len(sent) != 2 or sent[1].get("reasoning_effort") != "low" or not result or result.data != {"ok": 1}:
        fails.append(f"잘린 답: 호출 {len(sent)}번, 결과 {result}")

    result, sent = run(module, [_reply("", "stop")])
    if len(sent) != 1 or result is not None:
        fails.append(f"빈 답(stop): 호출 {len(sent)}번")

    result, sent = run(module, [_reply("", "length"), _reply("", "length")])
    if len(sent) != 2 or result is not None:
        fails.append(f"두 번 다 빈 답: 호출 {len(sent)}번, 결과 {result}")
    return fails


CHECKS = [("요약 ask_json", lambda: check(summary_llm)), ("인사이트 ask_json", lambda: check(insight_llm))]

if __name__ == "__main__":
    failed = 0
    for name, fn in CHECKS:
        problems = fn()
        print(("PASS " if not problems else "FAIL ") + name + " — 보통 1번 · 한도 초과만 low 로 1번 더")
        for p in problems:
            print("   -", p)
        failed += bool(problems)
    print(f"\n{len(CHECKS) - failed}/{len(CHECKS)} 통과")
    sys.exit(1 if failed else 0)
