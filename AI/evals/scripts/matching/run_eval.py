import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent.parent.parent / ".env")

from matching.graph import GRAPH  # noqa: E402
from matching.schemas import MatchingInput  # noqa: E402


def run_one(case: dict) -> dict:
    record = {
        "case_id": case.get("case_id"),
        "content": case["content"],
        "hint_name": case.get("hint_name"),
        "expected_child_id": case.get("expected_child_id"),
        "expected_status": case.get("expected_status"),
        "expected_hint_mismatch": case.get("expected_hint_mismatch"),
        "expected_mentioned_child_ids": case.get("expected_mentioned_child_ids"),
        "expected_multi_reason": case.get("expected_multi_reason", "__skip__"),
        "expected_candidates_child_ids": case.get("expected_candidates_child_ids"),
        "confusion_child_id": case.get("confusion_child_id"),
        "category": case.get("category"),
        "difficulty": case.get("difficulty"),
    }

    try:
        payload = MatchingInput(
            journal_entry_id=case["journal_entry_id"],
            content=case["content"],
            roster=case["roster"],
            raw_record_id=case.get("raw_record_id"),
            entry_date=case.get("entry_date"),
            hint_name=case.get("hint_name"),
            hint_birthdate=case.get("hint_birthdate"),
        )
        final = GRAPH.invoke(
            {
                "journal_entry_id": payload.journal_entry_id,
                "content": payload.content,
                "roster": payload.roster,
                "hint_name": payload.hint_name,
                "hint_birthdate": payload.hint_birthdate,
            }
        )
        status = final["status"]
        record.update(
            status=status,
            matched_child_id=final.get("matched_child_id"),
            confidence=final.get("confidence", 0.0),
            hint_mismatch=final.get("hint_mismatch", False),
            mentioned_child_ids=final.get("mentioned_child_ids", []),
            multi_reason=final.get("multi_reason"),
            candidates=(
                [dict(c) for c in final.get("candidates", [])]
                if status == "multi"
                else []
            ),
            llm_called=final.get("llm_called", False),
            error=None,
            diag={
                "has_exact": bool(final.get("has_exact")),
                "exact_child_ids": list(final.get("exact_child_ids") or []),
                "winner_named": final.get("matched_child_id")
                in set(final.get("exact_child_ids") or ()),
                "co_mention": bool(final.get("co_mention")),
                "llm_error": final.get("llm_error"),
                "llm_off_roster": bool(final.get("llm_off_roster")),
                "llm_ungrounded_pick": bool(final.get("llm_ungrounded_pick")),
                "n_candidates": len(final.get("candidates", [])),
                "all_candidates": [
                    c["child_id"] for c in final.get("candidates", [])
                ],
                "top": final.get("confidence", 0.0),
            },
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
            diag={},
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
