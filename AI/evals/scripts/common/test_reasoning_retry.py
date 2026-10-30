# AI/evals/scripts/common/test_reasoning_retry.py
"""
생각 한도에 걸렸을 때만 reasoning_effort=low 로 다시 부르는지 검사한다. Luna 를 부르지 않는다.

    python evals/scripts/common/test_reasoning_retry.py

Luna 프록시는 출력(생각 + 답)을 2000 토큰까지만 준다. 긴 입력이면 생각에 다 써서
답이 빈 채로 온다 (finish_reason=length, 2026-10-30 실데이터). 처음부터 low 로 두면
보통 입력의 품질이 떨어져서 (v3), 한도에 걸렸을 때만 낮춘다. 요약·인사이트가 같은 규칙이다.
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from insight import llm as insight_llm  # noqa: E402
from summary import llm as summary_llm  # noqa: E402

os.environ.setdefault("LUNA_API_URL", "http://luna.test")
os.environ.setdefault("LUNA_API_KEY", "test-key")


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
    """replies 를 차례로 돌려주고, 보낸 body 를 sent 에 모은다."""
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

    # ① 보통 응답: 한 번만 부르고, reasoning_effort 를 보내지 않는다 (모델 기본값)
    result, sent = run(module, [_reply('{"ok": 1}', "stop")])
    if len(sent) != 1 or "reasoning_effort" in sent[0] or result.usage["reasoning_retry"]:
        fails.append(f"보통 응답: 호출 {len(sent)}번, body={sent}")

    # ② 생각 한도 초과(빈 답 + length): low 로 한 번 더 불러 성공
    result, sent = run(module, [_reply("", "length"), _reply('{"ok": 1}', "stop")])
    if len(sent) != 2 or sent[1].get("reasoning_effort") != "low" or not result or result.data != {"ok": 1}:
        fails.append(f"한도 초과: 호출 {len(sent)}번, 두 번째 body={sent[1:]}")
    elif not result.usage["reasoning_retry"]:
        fails.append("한도 초과: usage 에 reasoning_retry 표시가 없다")

    # ③ 빈 답이지만 length 가 아님: 다시 부르지 않는다 (다른 원인이라 low 로 풀리지 않는다)
    result, sent = run(module, [_reply("", "stop")])
    if len(sent) != 1 or result is not None:
        fails.append(f"빈 답(stop): 호출 {len(sent)}번")

    # ④ low 로 다시 불러도 비면 실패로 올린다 (BE 가 재시도)
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
