"""
진행 지도(`common/progress.py`)가 노드 단계를 제대로 기록하고 지우는지 LLM 없이 고정한다 (#452).

    python evals/scripts/common/test_progress.py

대시보드 진행 현황이 `GET /progress` 로 "지금 어느 노드인지" 를 묻는다.
**진행 표시가 어떻게 망가져도 일지 처리 결과는 같아야 한다** — 이 파일이 지키는 것은 그 하나다.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from common import progress  # noqa: E402


class Clock:
    """테스트에서 시간을 직접 움직인다."""

    def __init__(self, t: float = 1000.0):
        self.t = t

    def __call__(self) -> float:
        return self.t


def boom():
    raise RuntimeError("시계 고장")


failed = 0


def ck(name, got, want, why):
    global failed
    ok = got == want
    print(f"  {'✓' if ok else '✗'} {name}")
    print(f"      기대 {want} · 실제 {got} — {why}")
    if not ok:
        failed += 1


def fresh(clock=None):
    progress.reset()
    progress._now = clock or Clock()
    return progress._now


def test_map():
    print("■ 들어간 노드가 보이고, 끝나면 지워진다")
    clock = fresh()
    with progress.begin("matching", journal_entry_id=120):
        progress.mark("extract")
        clock.t += 7.6
        progress.mark("llm_judge")
        ck("지금 노드와 걸린 초", progress.snapshot(),
           [{"agent": "matching", "journal_entry_id": 120, "node": "llm_judge", "elapsed_sec": 7}],
           "이슈 응답 모양 그대로. 키는 해시하지 않는다")
    ck("끝나면 비어 있다", progress.snapshot(), [], "엔드포인트 finally 에서 지운다")

    print("\n■ 여러 일이 동시에 돌면 시작한 순서대로 보인다")
    clock = fresh()
    with progress.begin("summary", child_id=7, entry_date="2026-11-08", institution_id=3):
        progress.mark("write")
        clock.t += 1
        with progress.begin("insight", child_id=7, period_from="2026-11-03", period_to="2026-11-09"):
            progress.mark("act")
            ck("두 줄", [(r["agent"], r["node"]) for r in progress.snapshot()],
               [("summary", "write"), ("insight", "act")], "BE 가 여러 건이면 가장 앞선 단계를 고른다")
            ck("요약 줄의 키", {k: v for k, v in progress.snapshot()[0].items() if k not in ("node", "elapsed_sec")},
               {"agent": "summary", "child_id": 7, "entry_date": "2026-11-08", "institution_id": 3},
               "요약은 아동 × 날짜 × 기관 묶음")

    print("\n■ 아직 노드에 들어가지 않은 일은 보이지 않는다")
    fresh()
    with progress.begin("validation", journal_entry_id=1):
        ck("노드 전", progress.snapshot(), [], "node 가 없으면 BE 가 번역할 문구도 없다")

    print("\n■ 본문 예외는 그대로 올라오고, 그래도 기록은 지워진다")
    fresh()
    raised = False
    try:
        with progress.begin("validation", journal_entry_id=2):
            progress.mark("act")
            raise ValueError("LLM 실패")
    except ValueError:
        raised = True
    ck("예외가 올라온다", raised, True, "503 처리는 main.py 가 한다. 진행 지도가 삼키면 안 된다")
    ck("기록은 지워진다", progress.snapshot(), [], "실패해도 남으면 영원히 '처리 중' 으로 보인다")

    print("\n■ begin 밖에서 부르면 아무 일도 없다 (evals 가 run_* 을 직접 부르는 경우)")
    fresh()
    progress.mark("write")
    ck("기록 없음", progress.snapshot(), [], "요청이 아니면 지도에 올리지 않는다")


def test_retry():
    print("\n■ 같은 일이 60초 안에 다시 오면 이어서 센다 (BE 재시도)")
    clock = fresh()
    with progress.begin("summary", child_id=7, entry_date="2026-11-08", institution_id=3):
        progress.mark("write")
        clock.t += 20
    clock.t += 59                                   # 503 뒤 59초 만에 재시도
    with progress.begin("summary", child_id=7, entry_date="2026-11-08", institution_id=3):
        progress.mark("gather")
        ck("이어서 센다", progress.snapshot()[0]["elapsed_sec"], 79, "처음 시작부터 20 + 59 초")

    clock.t += 61                                   # 61초 뒤면 새 일
    with progress.begin("summary", child_id=7, entry_date="2026-11-08", institution_id=3):
        progress.mark("gather")
        ck("새로 센다", progress.snapshot()[0]["elapsed_sec"], 0, "metrics.GAP_SEC 와 같은 60초 기준")

    clock.t += 5
    with progress.begin("summary", child_id=8, entry_date="2026-11-08", institution_id=3):
        progress.mark("gather")
        ck("다른 아이는 묶지 않는다", progress.snapshot()[0]["elapsed_sec"], 0, "키가 다르면 다른 일")

    print("\n■ 앞선 호출이 아직 도는데 재시도가 오면 같은 시작 시각을 쓴다")
    clock = fresh()
    with progress.begin("matching", journal_entry_id=5):
        progress.mark("llm_judge")
        clock.t += 30                               # BE 가 타임아웃 뒤 다시 보냄
        with progress.begin("matching", journal_entry_id=5):
            progress.mark("extract")
            ck("둘 다 30초", [r["elapsed_sec"] for r in progress.snapshot()], [30, 30],
               "AI 쪽 스레드는 취소되지 않아 둘이 겹칠 수 있다")


def test_stale():
    print("\n■ 혹시 남아도 5분 동안 소식이 없으면 지운다")
    clock = fresh()
    token = progress._start("insight", {"child_id": 7, "period_from": "a", "period_to": "b"})
    progress._current.set(token)
    progress.mark("act")
    clock.t += 299
    ck("4분 59초엔 남아 있다", len(progress.snapshot()), 1, "LLM 한 번이 길어도 5분 안에 끝난다")
    clock.t += 2
    ck("5분 넘으면 지운다", progress.snapshot(), [], "finally 를 못 거친 기록이 쌓이지 않게")
    progress._current.set(None)


def test_broken():
    print("\n■ 기록이 고장 나도 본문은 그대로 돈다")
    fresh()
    progress._now = boom
    ran = []
    with progress.begin("matching", journal_entry_id=9):
        progress.mark("extract")
        ran.append("본문")
    ck("본문이 돈다", ran, ["본문"], "시계가 예외를 던져도 begin·mark 가 삼킨다")
    ck("snapshot 은 예외를 올린다", _raises(progress.snapshot), True,
       "삼키는 것은 GET /progress 가 한다 (빈 목록)")

    fresh()
    progress._lock = None                           # with None: → TypeError
    try:
        with progress.begin("matching", journal_entry_id=9):
            progress.mark("extract")
            ran.append("자물쇠 없이")
    finally:
        progress._lock = progress.threading.Lock()
    ck("자물쇠가 없어도 돈다", ran[-1], "자물쇠 없이", "어떤 에러도 노드 실행에 닿지 않는다")


#: 이슈 #452 의 node 값. BE 문구표(#453)가 이 이름으로 번역한다 — 노드가 바뀌면 같이 고친다.
NODES = {
    "matching": ["extract", "shortlist", "llm_judge", "decide"],
    "validation": ["perceive", "plan", "act", "reflect"],
    "summary": ["gather", "write", "ground", "assemble"],
    "insight": ["perceive", "plan", "act", "reflect", "refine"],
}


def _graphs():
    from insight.graph import GRAPH as insight
    from matching.graph import GRAPH as matching
    from summary.graph import GRAPH as summary
    from validation.graph import GRAPH as validation
    return {"matching": matching, "validation": validation, "summary": summary, "insight": insight}


def _exact_matching_input():
    """이름이 그대로 적힌 아이 한 명 — 코드로 끝나고 모델을 부르지 않는다."""
    from matching.schemas import MatchingInput
    return MatchingInput(journal_entry_id=120, content="김민준이 블록을 높이 쌓았다.",
                         roster=[{"child_id": 1, "name": "김민준", "birthdate": "2020-03-01"},
                                 {"child_id": 2, "name": "이서연", "birthdate": "2020-05-02"}])


def test_graphs():
    from matching.graph import run_matching

    print("\n■ 4개 그래프의 노드가 이슈의 node 값과 같다")
    for agent, graph in _graphs().items():
        names = [n for n in graph.get_graph().nodes if n not in ("__start__", "__end__")]
        ck(f"{agent} 노드", names, NODES[agent], "늘어나면 BE 문구표(#453)도 같이 고친다")
        wrapped = [n for n in names if getattr(graph.builder.nodes[n].runnable.func, "progress_node", None) == n]
        ck(f"{agent} 감싸개", wrapped, names, "노드에 들어갈 때 기록하려면 전부 tracked 여야 한다")

    print("\n■ 실제 매칭 그래프를 돌리면 노드 순서대로 기록된다")
    fresh()
    seen = []
    original = progress.mark

    def spy(node):
        seen.append((node, progress._current.get() is not None))
        original(node)

    progress.mark = spy
    try:
        with progress.begin("matching", journal_entry_id=120):
            out = run_matching(_exact_matching_input())
    finally:
        progress.mark = original
    ck("모델 없이 끝났다", (out.status, out.llm_called), ("auto", False), "이 입력은 LLM 을 부르지 않는다")
    ck("노드 순서", [n for n, _ in seen], ["extract", "shortlist", "decide"], "명확하면 llm_judge 를 건너뛴다")
    ck("그래프 안에서도 요청이 보인다", all(ok for _, ok in seen), True,
       "LangGraph 가 ContextVar 를 노드까지 넘긴다")
    ck("끝나면 비어 있다", progress.snapshot(), [], "")

    print("\n■ 기록이 고장 나도 결과가 같다")
    fresh()
    want = run_matching(_exact_matching_input()).model_dump()
    progress._now = boom
    with progress.begin("matching", journal_entry_id=120):
        got = run_matching(_exact_matching_input()).model_dump()
    ck("같은 결과", got == want, True, "진행 표시는 일지 처리에 닿지 않는다")

    print("\n■ evals 처럼 run_* 을 직접 부르면 아무것도 남지 않는다")
    fresh()
    run_matching(_exact_matching_input())
    ck("기록 없음", (progress.snapshot(), progress._ended), ([], {}), "begin 을 거치지 않았다")


def test_find_claims():
    import insight.nodes as nodes
    from insight.llm import LlmError, LlmResult

    print("\n■ 리포트 refine 이 지난 기록을 찾는 동안은 find_claims, 끝나면 refine")
    state = {"history_index": {"h1": {"claim_id": "h1", "text": "점심을 남겼다", "entry_date": "2026-10-01",
                                      "institution_type": "KINDERGARTEN"}},
             "claim_index": {}, "period_from": "2026-11-03"}
    item = {"raw": {"claim_ids": ["c1"]}, "reasons": []}

    def run(fail: bool):
        seen = {}

        def fake_ask(instructions, user, tools, run_tool, **kw):
            seen["before"] = progress.snapshot()[0]["node"]
            run_tool("find_claims", {"situation": "점심"})
            seen["during"] = progress.snapshot()[0]["node"]
            if fail:
                raise LlmError("시간 초과", "timeout")
            return LlmResult(data={}, usage={"tool_calls": 1})

        fresh()
        saved = nodes.ask_with_tools, nodes.build_lookup_messages
        nodes.ask_with_tools = fake_ask
        nodes.build_lookup_messages = lambda *a, **k: ("지시", "입력")
        try:
            with progress.begin("insight", child_id=7, period_from="2026-11-03", period_to="2026-11-09"):
                progress.mark("refine")
                nodes._lookup(state, item, set())
                seen["after"] = progress.snapshot()[0]["node"]
        finally:
            nodes.ask_with_tools, nodes.build_lookup_messages = saved
        return seen

    for fail in (False, True):
        tag = "Luna 실패" if fail else "성공"
        seen = run(fail)
        ck(f"{tag}: 찾기 전", seen["before"], "refine", "도구를 부르기 전엔 refine")
        ck(f"{tag}: 찾는 중", seen["during"], "find_claims", "\"지난 기록을 찾아보고 있어요\"")
        ck(f"{tag}: 끝난 뒤", seen["after"], "refine", "실패해도 refine 으로 되돌린다")


def test_endpoints():
    import asyncio
    import inspect

    import main
    from common.errors import LlmUnavailable
    from fastapi.testclient import TestClient
    from insight.schemas import InsightInput
    from summary.schemas import SummaryInput
    from validation.schemas import ValidationInput

    cases = [
        ("matching", main.matching, "run_matching", _exact_matching_input(),
         {"journal_entry_id": 120}),
        ("validation", main.validation, "run_validation",
         ValidationInput.model_construct(journal_entry_id=123), {"journal_entry_id": 123}),
        ("summary", main.summary, "run_summary",
         SummaryInput.model_construct(child_id=7, entry_date="2026-11-08", institution_id=3),
         {"child_id": 7, "entry_date": "2026-11-08", "institution_id": 3}),
        ("insight", main.insight, "run_insight",
         InsightInput.model_construct(child_id=7, period_from="2026-11-03", period_to="2026-11-09"),
         {"child_id": 7, "period_from": "2026-11-03", "period_to": "2026-11-09"}),
    ]

    print("\n■ 엔드포인트가 도는 동안 GET /progress 에 그 일이 보이고, 끝나면 사라진다")
    for agent, endpoint, run_name, payload, ident in cases:
        for fail in (False, True):
            fresh()
            seen = []

            def fake(_payload, fail=fail):
                progress.mark("act")
                seen.extend(asyncio.run(main.read_progress()))
                if fail:
                    raise LlmUnavailable("Luna 호출 실패")
                return "결과"

            saved = getattr(main, run_name)
            setattr(main, run_name, fake)
            try:
                got = endpoint(payload)
            except LlmUnavailable:
                got = "503"
            finally:
                setattr(main, run_name, saved)
            tag = f"{agent}{' (503)' if fail else ''}"
            ck(f"{tag} 도는 중", seen, [{"agent": agent, **ident, "node": "act", "elapsed_sec": 0}],
               "BE 가 이 키로 진행 현황 줄을 찾는다")
            ck(f"{tag} 끝난 뒤", (got, asyncio.run(main.read_progress())), ("503" if fail else "결과", []),
               "응답은 그대로, 기록은 finally 에서 지운다")

    print("\n■ GET /progress 는 async 이고, 조회가 고장 나도 빈 목록이다")
    ck("async def", inspect.iscoroutinefunction(main.read_progress), True,
       "LLM 호출이 붙잡은 작업 스레드 풀을 기다리지 않는다")
    fresh()
    with progress.begin("validation", journal_entry_id=123):
        progress.mark("act")
        body = TestClient(main.app).get("/progress").json()
    ck("HTTP 응답 모양", body, [{"agent": "validation", "journal_entry_id": 123, "node": "act", "elapsed_sec": 0}],
       "이슈 #452 응답 모양")
    progress._now = boom
    res = TestClient(main.app).get("/progress")
    ck("고장 나면 200 []", (res.status_code, res.json()), (200, []), "BE 는 빈 목록이면 기존 고정 문구를 띄운다")


def _raises(fn) -> bool:
    try:
        fn()
    except Exception:
        return True
    return False


def main():
    test_map()
    test_retry()
    test_stale()
    test_broken()
    test_graphs()
    test_find_claims()
    test_endpoints()
    progress.reset()
    progress._now = progress.time.monotonic

    print(f"\n{'통과' if not failed else f'실패 {failed}건'}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
