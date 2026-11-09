import hashlib
import threading
import time
from collections import defaultdict, deque
from contextlib import contextmanager
from datetime import datetime, timezone

from .errors import LlmUnavailable

GAP_SEC = 60.0

MAX_RECENT = 4000

_lock = threading.Lock()
_counts: dict[tuple[str, str], int] = defaultdict(int)
_recent: deque = deque(maxlen=MAX_RECENT)
_started = datetime.now(timezone.utc)


def key_of(*parts) -> str:
    raw = "/".join("" if p is None else str(p) for p in parts)
    return hashlib.sha256(raw.encode()).hexdigest()[:12]


def record(path: str, key: str, outcome: str, elapsed: float) -> None:
    with _lock:
        _counts[(path, outcome)] += 1
        _recent.append((path, key, time.monotonic(), outcome, elapsed))


@contextmanager
def track(path: str, key: str):
    start = time.monotonic()
    outcome = "ok"
    try:
        yield
    except LlmUnavailable as exc:
        failure = getattr(exc, "failure", "unknown")
        outcome = exc.reason if failure == "unknown" else f"{exc.reason}:{failure}"
        raise
    except Exception:
        outcome = "error"
        raise
    finally:
        record(path, key, outcome, time.monotonic() - start)


def snapshot(gap_sec: float = GAP_SEC) -> dict:
    with _lock:
        counts: dict[str, dict[str, int]] = defaultdict(dict)
        for (path, outcome), n in _counts.items():
            counts[path][outcome] = n
        rows = list(_recent)

    groups: dict[tuple[str, str], list] = defaultdict(list)
    for path, key, at, outcome, elapsed in rows:
        bucket = groups[(path, key)]
        if bucket and at - bucket[-1][-1][0] > gap_sec:
            bucket.append([])
        elif not bucket:
            bucket.append([])
        bucket[-1].append((at, outcome, elapsed))

    repeats = []
    for (path, key), buckets in groups.items():
        for calls in buckets:
            if len(calls) < 2:
                continue
            repeats.append({
                "path": path,
                "key": key,
                "calls": len(calls),
                "span_sec": round(calls[-1][0] - calls[0][0], 2),
                "outcomes": [c[1] for c in calls],
            })
    repeats.sort(key=lambda r: r["calls"], reverse=True)

    return {
        "since": _started.isoformat(timespec="seconds"),
        "window": {"gap_sec": gap_sec, "kept": len(rows), "max": MAX_RECENT},
        "counts": dict(counts),
        "repeats": repeats[:50],
    }


def reset() -> None:
    with _lock:
        _counts.clear()
        _recent.clear()
