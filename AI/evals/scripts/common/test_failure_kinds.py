# AI/evals/scripts/common/test_failure_kinds.py
"""
Luna 실패 종류(timeout · empty_response …)가 끝까지 실려 가는지 검사한다. Luna 를 부르지 않는다.

    python evals/scripts/common/test_failure_kinds.py

멘토 A1 (2026-10-29): 빈 응답과 타임아웃을 따로 기록하라. 지금은 요약·인사이트만 한다.

    ① llm.py      requests.post 를 가짜로 바꿔 실패마다 LlmError.kind 가 맞는지
    ② graph.py    ask_json 이 실패하면 LlmUnavailable.failure 로 이어지는지
    ③ metrics.py  /metrics 에 "llm_unavailable:timeout" 처럼 남는지
"""

import os
import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import requests  # noqa: E402

from common import metrics  # noqa: E402
from common.errors import LlmUnavailable  # noqa: E402
from insight import llm as insight_llm  # noqa: E402
from insight import nodes as insight_nodes  # noqa: E402
from insight.graph import run_insight  # noqa: E402
from insight.schemas import InsightInput  # noqa: E402
from summary import llm as summary_llm  # noqa: E402
from summary import nodes as summary_nodes  # noqa: E402
from summary.graph import run_summary  # noqa: E402
from summary.schemas import SourceEntry, SummaryInput  # noqa: E402

os.environ.setdefault("LUNA_API_URL", "http://luna.test")
os.environ.setdefault("LUNA_API_KEY", "test-key")


class FakeResponse:
    def __init__(self, body=None, text=None):
        self._body, self.text = body, text or ""

    def raise_for_status(self):
        pass

    def json(self):
        if self._body is None:
            raise ValueError("not json")
        return self._body


def _openai(content):
    return {"choices": [{"message": {"content": content}}], "usage": {}}


def _post_returns(resp):
    return lambda *a, **k: resp


def _post_raises(exc):
    def post(*a, **k):
        raise exc
    return post


#: (이름, 가짜 requests.post, 기대 kind)
LLM_CASES = [
    ("타임아웃", _post_raises(requests.Timeout("read timed out")), "timeout"),
    ("연결 실패", _post_raises(requests.ConnectionError("refused")), "http_error"),
    ("본문이 JSON 아님", _post_returns(FakeResponse(text="<html>")), "bad_json"),
    ("OpenAI 모양 아님", _post_returns(FakeResponse({"error": "x"})), "bad_response"),
    ("content 빈 문자열", _post_returns(FakeResponse(_openai(""))), "empty_response"),
    ("content 가 {}", _post_returns(FakeResponse(_openai("{}"))), "empty_response"),
    ("JSON 깨짐", _post_returns(FakeResponse(_openai("{\"claims\": ["))), "bad_json"),
]


def check_llm_kinds(module) -> list[str]:
    fails = []
    original = module.requests.post
    try:
        for name, post, expected in LLM_CASES:
            module.requests.post = post
            try:
                module.ask_json([{"role": "user", "content": "x"}])
                fails.append(f"{name}: 예외가 안 났다")
            except module.LlmError as exc:
                if exc.kind != expected:
                    fails.append(f"{name}: kind={exc.kind}, 기대 {expected}")
        # 정상 응답은 그대로 통과
        module.requests.post = _post_returns(FakeResponse(_openai('{"claims": []}')))
        if module.ask_json([{"role": "user", "content": "x"}]).data != {"claims": []}:
            fails.append("정상 응답이 바뀌었다")
    finally:
        module.requests.post = original
    return fails


def check_not_configured(module) -> list[str]:
    saved = os.environ.pop("LUNA_API_URL")
    try:
        module.ask_json([])
        return ["URL 없음: 예외가 안 났다"]
    except module.LlmError as exc:
        return [] if exc.kind == "not_configured" else [f"URL 없음: kind={exc.kind}"]
    finally:
        os.environ["LUNA_API_URL"] = saved


def _fail_with(nodes_module, llm_module, kind):
    def boom(messages):
        raise llm_module.LlmError("가짜 실패", kind)
    nodes_module.ask_json = boom


def _summary_payload():
    return SummaryInput(child_id=1, child_name="아동A", entry_date="2026-04-01", institution_id=1,
                        sources=[SourceEntry(journal_entry_id=11, content="블록을 쌓음.")])


def _insight_payload():
    claim = lambda cid, d: {"claim_id": cid, "text": f"{cid} 문장", "entry_date": d,
                            "institution_id": 1, "institution_type": "센터",
                            "evidence": [{"journal_entry_id": 1, "quote": "인용"}]}
    return InsightInput(child_id=1, child_name="아동A", period_from="2026-04-01", period_to="2026-04-30",
                        claims=[claim("1-0", "2026-04-01"), claim("2-0", "2026-04-08")],
                        consented_institutions=[{"institution_id": 1, "institution_type": "센터"}])


def check_graph(run, payload, nodes_module, llm_module) -> list[str]:
    fails = []
    original = nodes_module.ask_json
    try:
        for kind in ("timeout", "empty_response"):
            _fail_with(nodes_module, llm_module, kind)
            try:
                run(payload)
                fails.append(f"{kind}: 503 이 안 났다")
            except LlmUnavailable as exc:
                if exc.reason != "llm_unavailable" or exc.failure != kind:
                    fails.append(f"{kind}: reason={exc.reason} failure={exc.failure}")
    finally:
        nodes_module.ask_json = original
    return fails


def check_metrics() -> list[str]:
    metrics.reset()
    for failure in ("timeout", "unknown"):
        try:
            with metrics.track("/summary", "k"):
                raise LlmUnavailable("x", failure=failure)
        except LlmUnavailable:
            pass
    counts = metrics.snapshot()["counts"].get("/summary", {})
    metrics.reset()
    expected = {"llm_unavailable:timeout": 1, "llm_unavailable": 1}
    return [] if counts == expected else [f"집계 {counts}, 기대 {expected}"]


CHECKS = [
    ("요약 llm.py 실패 종류 8가지", lambda: check_llm_kinds(summary_llm) + check_not_configured(summary_llm)),
    ("인사이트 llm.py 실패 종류 8가지", lambda: check_llm_kinds(insight_llm) + check_not_configured(insight_llm)),
    ("요약 503 에 failure 가 실림", lambda: check_graph(run_summary, _summary_payload(), summary_nodes, summary_llm)),
    ("인사이트 503 에 failure 가 실림", lambda: check_graph(run_insight, _insight_payload(), insight_nodes, insight_llm)),
    ("/metrics 에 종류별로 셈", check_metrics),
]


if __name__ == "__main__":
    failed = 0
    for name, fn in CHECKS:
        try:
            problems = fn()
        except Exception as exc:  # 예상 못 한 에러도 실패로 센다
            problems = [f"{type(exc).__name__}: {exc}"]
        print(("PASS " if not problems else "FAIL ") + name)
        for p in problems:
            print("   -", p)
        failed += bool(problems)
    print(f"\n{len(CHECKS) - failed}/{len(CHECKS)} 통과")
    sys.exit(1 if failed else 0)
