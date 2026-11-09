import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
os.environ.setdefault("INSIGHT_API_URL", "http://luna.test")
os.environ.setdefault("API_KEY", "test-key")

import requests  # noqa: E402

from insight import llm  # noqa: E402

CALL = {"output": [{"type": "function_call", "id": "fc_1", "call_id": "c1", "name": "find", "arguments": "{}"}],
        "id": "resp_1", "status": "completed"}
DONE = {"output": [{"type": "message", "content": [{"type": "output_text", "text": '{"ok": 1}'}]}],
        "id": "resp_2", "status": "completed"}
REJECT = '{"error":{"message":"Unsupported parameter(s) for /v1/responses on this model: previous_response_id."}}'


class Fake:
    def __init__(self, body=None, status=200, text=""):
        self.body, self.status_code, self.text = body, status, text or json.dumps(body or {})

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code} Client Error", response=self)

    def json(self):
        return self.body


def run(server):
    sent = []

    def post(url, json, **kw):
        sent.append(json)
        return server(json, len(sent))

    original = llm.requests.post
    llm.requests.post = post
    try:
        result = llm.ask_with_tools("지시", "질문", [{"type": "function", "name": "find"}], lambda n, a: {"r": 1})
        return sent, result, None
    except llm.LlmError as exc:
        return sent, None, exc
    finally:
        llm.requests.post = original


def main() -> int:
    fails = []

    sent, result, err = run(lambda body, n: Fake(CALL if n == 1 else DONE))
    if err or result.data != {"ok": 1} or len(sent) != 2 or sent[1].get("previous_response_id") != "resp_1":
        fails.append(f"① 이어 보내기: {len(sent)}번 {err}")
    else:
        print("PASS ① 루나 방식 — previous_response_id 로 이어서 (2번 호출)")

    def claude(body, n):
        if n == 1:
            return Fake(CALL)
        if "previous_response_id" in body:
            return Fake(status=400, text=REJECT)
        return Fake(DONE)
    sent, result, err = run(claude)
    last = sent[-1].get("input") if sent else None
    kinds = [item.get("type") or item.get("role") for item in last or []]
    if err or result.data != {"ok": 1} or len(sent) != 3 or kinds != ["user", "function_call", "function_call_output"]:
        fails.append(f"② 통째로 다시 보내기: {len(sent)}번 {kinds} {err}")
    elif result.usage.get("tool_calls") != 1 or "previous_response_id" in sent[-1]:
        fails.append("② tool_calls 기록이나 body 가 틀림")
    else:
        print("PASS ② claude 방식 — 거절되면 대화를 통째로 다시 (3번 호출)")

    sent, result, err = run(lambda body, n: Fake(status=400, text='{"error":"bad tools"}'))
    if not err or err.kind != "http_error" or "bad tools" not in str(err) or len(sent) != 1:
        fails.append(f"③ 다른 400: {err}")
    else:
        print("PASS ③ 다른 400 은 그대로 에러, 문구가 남음")

    for f in fails:
        print("FAIL", f)
    print("\n전부 통과 (3/3)" if not fails else f"\n실패 {len(fails)}건")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
