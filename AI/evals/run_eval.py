"""
매칭 에이전트를 테스트 데이터 전체에 돌려 결과 파일을 만든다.

    python evals/run_eval.py <입력.json> <결과.json> [--limit N] [--workers N]

LLM 을 부르므로 시간과 토큰이 든다 (1,070건 기준 약 8분).
채점은 결과 파일만 있으면 되므로 score.py 로 따로 돌린다 —
임계값을 바꿔가며 여러 번 채점할 때 매번 다시 부르지 않기 위해서다.
"""

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

from matching.graph import run_matching  # noqa: E402
from matching.schemas import MatchingInput  # noqa: E402


def run_one(case: dict) -> dict:
    """한 건을 그래프에 태우고, 채점에 필요한 것만 남긴다."""
    record = {
        "case_id": case.get("case_id"),
        "content": case["content"],
        "hint_name": case.get("hint_name"),
        # 정답 필드들. 없으면 그 항목은 채점에서 빠진다.
        "expected_child_id": case.get("expected_child_id"),
        "expected_status": case.get("expected_status"),
        "expected_hint_mismatch": case.get("expected_hint_mismatch"),
        "expected_mentioned_child_ids": case.get("expected_mentioned_child_ids"),
        "confusion_child_id": case.get("confusion_child_id"),
    }

    try:
        out = run_matching(
            MatchingInput(
                journal_entry_id=case["journal_entry_id"],
                content=case["content"],
                roster=case["roster"],
                raw_record_id=case.get("raw_record_id"),
                entry_date=case.get("entry_date"),
                hint_name=case.get("hint_name"),
                hint_birthdate=case.get("hint_birthdate"),
            )
        )
        record.update(
            status=out.status,
            matched_child_id=out.matched_child_id,
            confidence=out.confidence,
            hint_mismatch=out.hint_mismatch,
            mentioned_child_ids=out.mentioned_child_ids,
            multi_reason=out.multi_reason,
            candidates=[c.model_dump() for c in out.candidates],
            llm_called=out.llm_called,
            error=None,
        )
    except Exception as exc:  # noqa: BLE001 - 한 건이 죽어도 나머지는 돌려야 한다
        record.update(
            status="ERROR",
            matched_child_id=None,
            confidence=0.0,
            hint_mismatch=False,
            mentioned_child_ids=[],
            multi_reason=None,
            candidates=[],
            llm_called=False,
            error=f"{type(exc).__name__}: {exc}",
        )

    return record


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="테스트 입력 JSON (MatchingInput 배열 + expected_* 필드)")
    ap.add_argument("dst", help="결과를 쓸 경로")
    ap.add_argument("--limit", type=int, default=0, help="앞에서 N 건만 (0 = 전체)")
    ap.add_argument("--workers", type=int, default=8, help="동시 호출 수")
    args = ap.parse_args()

    cases = json.loads(Path(args.src).read_text(encoding="utf-8"))
    if args.limit:
        cases = cases[: args.limit]

    done = [0]
    started = time.time()

    def work(case: dict) -> dict:
        result = run_one(case)
        done[0] += 1
        if done[0] % 50 == 0:
            elapsed = time.time() - started
            left = elapsed / done[0] * (len(cases) - done[0])
            print(f"  {done[0]}/{len(cases)}  {elapsed:.0f}s  (남은 예상 {left:.0f}s)", flush=True)
        return result

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(work, cases))

    Path(args.dst).write_text(
        json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    print(f"완료 {len(results)}건 / {time.time() - started:.0f}s → {args.dst}")


if __name__ == "__main__":
    main()
