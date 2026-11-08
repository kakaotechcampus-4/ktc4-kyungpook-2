# AI/common/progress.py
"""
에이전트가 **지금 어느 노드에 있는지** 기억하는 진행 지도 (#452).

대시보드 진행 현황에 "요약 문장을 쓰고 있어요" 를 띄우려 한다. 본 호출(`/matching` 등)은
결과를 다 만든 뒤에 한 번만 응답하므로, 중간 단계는 BE 가 `GET /progress` 로 따로 묻는다.

    엔드포인트  ── begin(agent, 키) ──┐   그 요청을 지도에 올리고, 끝나면(finally) 내린다
    그래프 노드 ── tracked(이름, 함수) ┘   노드에 들어갈 때 mark(이름)
    GET /progress ── snapshot()             지금 지도를 그대로 돌려준다

어느 요청인지는 ContextVar 로 넘긴다. 노드 함수와 `run_*` 은 그대로 두려는 것이다.
evals 는 `run_*` 을 직접 불러 begin 을 거치지 않으므로 아무것도 기록되지 않는다.

**진행 표시가 어떻게 망가져도 노드 실행과 응답에는 닿으면 안 된다.** 그래서 기록 쪽
(begin · mark)은 모든 예외를 삼킨다. 조회(snapshot)의 예외는 GET /progress 가 빈 목록으로 바꾼다.

키는 해시하지 않는다 — AI 서버는 외부에 열려 있지 않고 BE 만 부른다. 해시하면 BE 가
어느 일지인지 되돌릴 수 없다. (metrics.py 는 오래 남기는 집계라 해시한다)

uvicorn 프로세스가 하나라 메모리 지도로 충분하다. 프로세스를 늘리면 다시 봐야 한다.
"""

import functools
import inspect
import itertools
import threading
import time
from contextlib import contextmanager
from contextvars import ContextVar

from .metrics import GAP_SEC

#: 같은 일이 이 간격 안에 다시 오면 BE 재시도로 보고 시간을 이어서 센다.
#: metrics.py 가 재시도를 한 묶음으로 세는 기준과 같다.
RETRY_GAP_SEC = GAP_SEC

#: finally 를 거치지 못하고 남은 기록을 지우는 기준(초). 마지막 mark 부터 잰다.
#: LLM 한 번은 이보다 훨씬 짧다 — 그보다 오래 소식이 없으면 이미 끝난 일이다.
STALE_SEC = 300.0

#: 테스트에서 바꿔 끼운다.
_now = time.monotonic

_lock = threading.Lock()
_tokens = itertools.count(1)
#: 지금 도는 요청. token → {agent, ident, key, node, started, touched}
_running: dict[int, dict] = {}
#: 최근에 끝난 일. key → (started, ended_at). 재시도 때 시작 시각을 이어받는다.
#: 성공·실패를 가리지 않는다 — BE 타임아웃은 AI 쪽에서는 늦게라도 200 으로 끝난다.
#: [한계] AI 는 재시도와 새 일을 구분하지 못한다. 성공 뒤 60초 안에 같은 키로 온 새 일도
#: 이어 센다 (metrics.py 와 같은 기준, #452 결정). 정확히 하려면 BE 가 시도 ID 를 보내야 한다.
_ended: dict[tuple, tuple[float, float]] = {}
#: 지금 요청의 token. begin 밖이면 None.
_current: ContextVar[int | None] = ContextVar("progress_token", default=None)


def _key(agent: str, ident: dict) -> tuple:
    return (agent, *ident.values())


def _prune(now: float) -> None:
    """lock 안에서 부른다."""
    for token in [t for t, e in _running.items() if now - e["touched"] > STALE_SEC]:
        del _running[token]
    for key in [k for k, (_, ended_at) in _ended.items() if now - ended_at > RETRY_GAP_SEC]:
        del _ended[key]


def _start(agent: str, ident: dict) -> int:
    now = _now()
    key = _key(agent, ident)
    with _lock:
        _prune(now)
        started = [e["started"] for e in _running.values() if e["key"] == key]
        if key in _ended:
            started.append(_ended[key][0])
        token = next(_tokens)
        _running[token] = {"agent": agent, "ident": dict(ident), "key": key, "node": None,
                           "started": min(started, default=now), "touched": now}
    return token


def _finish(token: int, failed: bool) -> None:
    """
    요청을 지도에서 내린다.

    **성공했으면** 같은 키로 먼저 들어와 아직 도는 요청도 함께 내린다 — BE 가 타임아웃으로
    버리고 다시 보낸 호출이다. 남겨 두면 BE 가 결과를 받은 일이 다시 '처리 중' 으로 보인다.
    실패했으면 BE 가 또 보내므로 남겨 둔다. 다음 시도가 노드에 들어가기 전까지 줄이 비지 않게.
    """
    now = _now()
    with _lock:
        entry = _running.pop(token, None)
        if entry is None:
            return
        if not failed:
            for older in [t for t, e in _running.items() if e["key"] == entry["key"] and t < token]:
                del _running[older]
        _ended[entry["key"]] = (entry["started"], now)


def _discard(token: int) -> None:
    with _lock:
        _running.pop(token, None)


@contextmanager
def begin(agent: str, **ident):
    """
    한 요청을 지도에 올린다. 엔드포인트에서 run_* 을 이걸로 감싼다.

    ident 는 BE 가 그 일을 알아보는 키다 (매칭·검증 journal_entry_id, 요약 아동·날짜·기관 등).
    넘긴 순서대로 응답에 나간다.
    """
    token = reset = None
    try:
        token = _start(agent, ident)
    except Exception:
        pass
    try:
        reset = _current.set(token)          # 등록에 실패했어도 None 으로 덮어 바깥 요청에 적지 않는다
    except Exception:
        # 요청을 못 알아보면 mark 가 이 기록을 고칠 수 없다. 지도에서 내린다.
        if token is not None:
            _quietly(_discard, token)
            token = None

    failed = False
    try:
        yield
    except BaseException:
        failed = True
        raise
    finally:
        if reset is not None:
            _quietly(_current.reset, reset)
        if token is not None:
            _quietly(_finish, token, failed)


def _quietly(fn, *args) -> None:
    try:
        fn(*args)
    except Exception:
        pass


def mark(node: str) -> None:
    """지금 요청이 node 에 들어갔다고 적는다. begin 밖이면 아무것도 하지 않는다."""
    try:
        token = _current.get()
        if token is None:
            return
        now = _now()
        with _lock:
            entry = _running.get(token)
            if entry is not None:
                entry["node"] = node
                entry["touched"] = now
    except Exception:
        pass


def tracked(name: str, fn):
    """
    노드 함수를 감싼다: `graph.add_node("write", tracked("write", write))`.

    노드에 **들어갈 때** 기록한다. 노드 함수는 건드리지 않는다.
    **state 하나만 받는 노드 전용이다.** config 등을 꼭 받아야 하는 노드는 감쌀 때 TypeError —
    조용히 값이 빠지느니 서버가 뜰 때(그래프를 만들 때) 터지는 편이 낫다.
    """
    required = [p for p in inspect.signature(fn).parameters.values()
                if p.default is p.empty and p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
    if len(required) > 1:
        raise TypeError(f"tracked 는 state 하나만 받는 노드만 감싼다: {name}{inspect.signature(fn)}")

    # updated=() — 이름·문서·타입 힌트만 옮기고 fn.__dict__ 는 옮기지 않는다.
    @functools.wraps(fn, updated=())
    def node(state):
        mark(name)
        return fn(state)

    # wraps 는 __wrapped__ 를 남긴다. 그러면 inspect.signature 가 원래 함수를 따라가, 노드가
    # config 를 받으면 LangGraph 가 이 감싸개에 config 를 넘긴다. 감싸개는 state 하나만 받으므로
    # 지운다 (타입 힌트는 남아 입력 스키마 추론은 같다).
    del node.__wrapped__
    node.progress_node = name
    return node


def snapshot() -> list[dict]:
    """
    지금 도는 일들. 시작한 순서대로.

    같은 일이 둘 겹치면(BE 가 타임아웃 뒤 다시 보냈는데 앞선 호출이 아직 도는 경우)
    **나중에 들어온 요청 한 줄만** 낸다 — BE 가 기다리는 것은 그쪽이다. 시작 시각은 처음 것이다.

        [{"agent": "matching", "journal_entry_id": 120, "node": "llm_judge", "elapsed_sec": 7}, ...]

    아직 노드에 들어가지 않은 일은 뺀다 — BE 가 번역할 문구가 없다.
    """
    now = _now()
    with _lock:
        _prune(now)
        # token 이 클수록 나중 요청. 노드에 아직 안 들어간 재시도는 앞선 호출을 가리지 않는다.
        latest = {e["key"]: e for _, e in sorted(_running.items()) if e["node"] is not None}
        entries = sorted(latest.values(), key=lambda e: e["started"])
        rows = [{"agent": e["agent"], **e["ident"], "node": e["node"],
                 "elapsed_sec": int(now - e["started"])}
                for e in entries]
    return rows


def reset() -> None:
    """테스트 전용. 운영에서는 부르지 않는다."""
    with _lock:
        _running.clear()
        _ended.clear()
