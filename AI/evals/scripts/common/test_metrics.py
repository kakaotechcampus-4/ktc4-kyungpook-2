# AI/evals/scripts/common/test_metrics.py
"""
호출 수 집계가 재시도를 제대로 묶는지 LLM 없이 고정한다.

    python evals/scripts/common/test_metrics.py

이 집계로 보려는 것은 하나다 — **BE 설정보다 많이 오는가** (`#153`).
BE 가 `retry-backoffs: 2s,5s` 면 한 묶음의 calls 가 3 을 넘으면 안 된다.
"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from common import metrics  # noqa: E402
from common.errors import LlmUnavailable  # noqa: E402


class NoGrounded(LlmUnavailable):
    reason = "no_grounded_claims"
    message = "근거가 남은 문장이 없음"


def call(path, key, exc=None):
    try:
        with metrics.track(path, key):
            if exc:
                raise exc
    except LlmUnavailable:
        pass


def main():
    failed = 0

    def ck(name, got, want, why):
        nonlocal failed
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {name}")
        print(f"      기대 {want} · 실제 {got} — {why}")
        if not ok:
            failed += 1

    print("■ 같은 재료로 다시 오면 한 묶음으로 센다")
    metrics.reset()
    k = metrics.key_of(8, "2026-10-08", 3)
    for _ in range(6):
        call("/summary", k, NoGrounded("x"))
    snap = metrics.snapshot()
    ck("재시도 6번이 한 묶음", snap["repeats"][0]["calls"], 6,
       "#153 에서 실제로 나온 값. BE 설정은 3 이다")
    ck("결과 이름이 BE 와 같다", snap["counts"]["/summary"]["no_grounded_claims"], 6,
       "503 의 detail.reason 과 같은 이름으로 센다")

    print("\n■ 재료가 다르면 묶지 않는다")
    metrics.reset()
    for entry_id in (1, 2, 3):
        call("/validation", metrics.key_of(entry_id))
    snap = metrics.snapshot()
    ck("서로 다른 일지는 묶음 아님", len(snap["repeats"]), 0,
       "한 번씩 온 것은 재시도가 아니다")
    ck("성공 수는 센다", snap["counts"]["/validation"]["ok"], 3, "호출 총량 확인용")

    print("\n■ 간격이 벌어지면 다른 호출로 본다")
    metrics.reset()
    k = metrics.key_of(1)
    call("/matching", k)
    call("/matching", k)
    # GAP_SEC 보다 멀어진 뒤의 호출은 새 묶음이다.
    metrics.record("/matching", k, "ok", 0.1)
    with metrics._lock:
        path, key, at, outcome, elapsed = metrics._recent[-1]
        metrics._recent[-1] = (path, key, at + metrics.GAP_SEC + 1, outcome, elapsed)
    snap = metrics.snapshot()
    ck("앞의 둘만 한 묶음", [r["calls"] for r in snap["repeats"]], [2],
       "워커가 다음 차례에 같은 묶음을 다시 집은 것은 재시도가 아니다")

    print("\n■ 키에 원본을 남기지 않는다")
    metrics.reset()
    k = metrics.key_of(8, "2026-10-08", 3)
    snap_key = metrics.key_of(8, "2026-10-08", 3)
    ck("같은 재료면 같은 키", k, snap_key, "재시도를 알아보려면 같아야 한다")
    ck("원본이 안 보인다", any(s in k for s in ("8", "2026")) and len(k) == 12, False,
       "child_id·날짜가 그대로 드러나면 조회만으로 누가 언제 처리됐는지 안다")

    if failed:
        print(f"\n  실패 {failed}건")
        sys.exit(1)
    print("\n  전부 통과 ✅")


if __name__ == "__main__":
    main()
