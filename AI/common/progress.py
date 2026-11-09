import functools
import inspect
import itertools
import threading
import time
from contextlib import contextmanager
from contextvars import ContextVar

from .metrics import GAP_SEC

RETRY_GAP_SEC = GAP_SEC

STALE_SEC = 300.0

_now = time.monotonic

_lock = threading.Lock()
_tokens = itertools.count(1)
_running: dict[int, dict] = {}
_ended: dict[tuple, tuple[float, float]] = {}
_current: ContextVar[int | None] = ContextVar("progress_token", default=None)


def _key(agent: str, ident: dict) -> tuple:
    return (agent, *ident.values())


def _prune(now: float) -> None:
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
    token = reset = None
    try:
        token = _start(agent, ident)
    except Exception:
        pass
    try:
        reset = _current.set(token)
    except Exception:
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
    required = [p for p in inspect.signature(fn).parameters.values()
                if p.default is p.empty and p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
    if len(required) > 1:
        raise TypeError(f"tracked 는 state 하나만 받는 노드만 감싼다: {name}{inspect.signature(fn)}")

    @functools.wraps(fn, updated=())
    def node(state):
        mark(name)
        return fn(state)

    del node.__wrapped__
    node.progress_node = name
    return node


def snapshot() -> list[dict]:
    now = _now()
    with _lock:
        _prune(now)
        latest = {e["key"]: e for _, e in sorted(_running.items()) if e["node"] is not None}
        entries = sorted(latest.values(), key=lambda e: e["started"])
        rows = [{"agent": e["agent"], **e["ident"], "node": e["node"],
                 "elapsed_sec": int(now - e["started"])}
                for e in entries]
    return rows


def reset() -> None:
    with _lock:
        _running.clear()
        _ended.clear()
