# AI/common/metrics.py
"""
AI 서버가 **실제로 몇 번 불렸는지** 센다.

BE 는 `app.ai.retry-backoffs: 2s,5s` 로 한 번의 논리적 호출에 최대 3번 부르도록
설정돼 있는데, 2026-10-10 E2E 에서 AI 서버에는 **6번** 왔다 (`#153`). BE 로그와
AI 접근 로그를 사람이 눈으로 맞춰 봐야 알 수 있었다.

여기서 세면 `GET /metrics` 한 번으로 보인다. 그리고 BE 가 고친 뒤 실제로 3이
되는지를 **AI 쪽에서 독립적으로** 확인할 수 있다 — 블라인드 세트를 서로 만드는
것과 같은 이유다.

재시도는 같은 재료로 다시 오는 것이라 **요청 내용으로 알아본다.** 같은 key 가
짧은 간격으로 이어지면 한 묶음으로 센다.

⚠️ key 는 해시로만 남긴다. `child_id` 와 날짜를 그대로 두면 운영 중 조회만으로
어느 아이가 몇 시에 처리됐는지가 드러난다. 세는 데는 해시로 충분하다.
"""

import hashlib
import threading
import time
from collections import defaultdict, deque
from contextlib import contextmanager
from datetime import datetime, timezone

from .errors import LlmUnavailable

#: 한 묶음으로 볼 최대 간격(초). BE 재시도는 2초·5초 뒤라 넉넉히 잡아도
#: 서로 다른 논리적 호출이 섞이지 않는다. 요약 워커는 5초마다 돈다.
GAP_SEC = 60.0

#: 최근 요청만 들고 있는다. 서버가 재시작되면 비는 것이 정상이다 —
#: 장기 보관용이 아니라 "지금 몇 번 오고 있나" 를 보는 창이다.
MAX_RECENT = 4000

_lock = threading.Lock()
_counts: dict[tuple[str, str], int] = defaultdict(int)
_recent: deque = deque(maxlen=MAX_RECENT)
_started = datetime.now(timezone.utc)


def key_of(*parts) -> str:
    """재료를 식별하는 해시. 같은 재료로 다시 오면 같은 값이 나온다."""
    raw = "/".join("" if p is None else str(p) for p in parts)
    return hashlib.sha256(raw.encode()).hexdigest()[:12]


def record(path: str, key: str, outcome: str, elapsed: float) -> None:
    with _lock:
        _counts[(path, outcome)] += 1
        _recent.append((path, key, time.monotonic(), outcome, elapsed))


@contextmanager
def track(path: str, key: str):
    """
    한 번의 호출을 센다. 결과는 BE 가 보는 것과 같은 이름으로 남긴다 —
    `ok` 는 200, 나머지는 503 의 `detail.reason` 이다.
    """
    start = time.monotonic()
    outcome = "ok"
    try:
        yield
    except LlmUnavailable as exc:
        # 실패 종류를 알면 붙인다: "llm_unavailable:timeout". 모르면 reason 만.
        failure = getattr(exc, "failure", "unknown")
        outcome = exc.reason if failure == "unknown" else f"{exc.reason}:{failure}"
        raise
    except Exception:
        outcome = "error"
        raise
    finally:
        record(path, key, outcome, time.monotonic() - start)


def snapshot(gap_sec: float = GAP_SEC) -> dict:
    """
    지금까지의 집계. `repeats` 가 `#153` 을 보는 자리다.

    같은 key 가 `gap_sec` 안에 이어지면 한 묶음으로 세고, 2번 이상인 묶음만
    돌려준다. **BE 설정이 3이면 여기 `calls` 가 3을 넘으면 안 된다.**
    """
    with _lock:
        counts: dict[str, dict[str, int]] = defaultdict(dict)
        for (path, outcome), n in _counts.items():
            counts[path][outcome] = n
        rows = list(_recent)

    groups: dict[tuple[str, str], list] = defaultdict(list)
    for path, key, at, outcome, elapsed in rows:
        bucket = groups[(path, key)]
        if bucket and at - bucket[-1][-1][0] > gap_sec:
            bucket.append([])                      # 간격이 벌어지면 새 묶음
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
    """테스트 전용. 운영에서는 부르지 않는다."""
    with _lock:
        _counts.clear()
        _recent.clear()
