import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

AI_DIR = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(AI_DIR))

from dotenv import load_dotenv  # noqa: E402

load_dotenv(AI_DIR / ".env")

from summary.graph import GRAPH  # noqa: E402
from summary.schemas import SummaryInput  # noqa: E402

EXPECTED = (
    "expected_covered_entry_ids",
    "expected_droppable_entry_ids",
    "expected_facts",
    "expected_absent",
    "expected_status",
)

PASSTHROUGH = ("case_id", "category", "difficulty", "note")


def _claim_record(claim: dict, by_id: dict) -> dict:
    evidence = []
    for e in claim["evidence"]:
        source_len = len(by_id.get(e["journal_entry_id"], ""))
        quote_len = len(e["quote"] or "")
        evidence.append(
            {
                "journal_entry_id": e["journal_entry_id"],
                "quote": e["quote"],
                "quote_chars": quote_len,
                "source_chars": source_len,
                "quote_ratio": round(quote_len / source_len, 4) if source_len else None,
            }
        )
    return {
        "text": claim["text"],
        "evidence": evidence,
        "multi_entry": len({e["journal_entry_id"] for e in evidence}) >= 2,
    }


def run_one(case: dict) -> dict:
    record = {k: case.get(k) for k in PASSTHROUGH}
    record.update({k: case.get(k) for k in EXPECTED})
    record["n_sources"] = len(case.get("sources") or [])

    try:
        payload = SummaryInput(**{k: v for k, v in case.items()
                                  if k in SummaryInput.model_fields})
        final = GRAPH.invoke(
            {
                "child_id": payload.child_id,
                "child_name": payload.child_name,
                "entry_date": payload.entry_date,
                "institution_id": payload.institution_id,
                "institution_name": payload.institution_name,
                "sources": payload.sources,
                "other_child_names": payload.other_child_names,
            }
        )
        by_id = final["by_id"]
        claims = [_claim_record(c, by_id) for c in final["claims"]]
        record.update(
            content=final["content"],
            claims=claims,
            covered_entry_ids=final["covered_entry_ids"],
            uncovered_entry_ids=final["uncovered_entry_ids"],
            status=(
                503
                if (payload.sources and not final["claims"] and final.get("raw_claims"))
                else 200
            ),
            no_content=bool(payload.sources) and not final.get("raw_claims"),
            needs_review=final.get("needs_review", False),
            review_reasons=final.get("review_reasons", []),
            llm_called=final.get("llm_called", False),
            error=final.get("llm_error"),
            diag={
                "dropped_evidence": final.get("dropped_evidence", 0),
                "dropped_claims": final.get("dropped_claims", []),
                "dropped_detail": final.get("dropped_detail", []),
                "dropped_detail": final.get("dropped_detail", []),
                "source_chars": sum(len(v) for v in by_id.values()),
                "content_chars": len(final["content"]),
                "usage": final.get("llm_usage") or {},
            },
        )
    except Exception as exc:  # noqa: BLE001 - 한 건이 죽어도 나머지는 돌려야 한다
        record.update(
            content="", claims=[], covered_entry_ids=[], uncovered_entry_ids=[],
            status=None, needs_review=False, review_reasons=[],
            llm_called=False, error=f"{type(exc).__name__}: {exc}", diag={},
        )
    return record


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="테스트 입력 JSON (SummaryInput 배열 + expected_* 필드)")
    ap.add_argument("dst", help="결과를 쓸 경로")
    ap.add_argument("--limit", type=int, default=0, help="앞에서 N 건만 (0 = 전체)")
    ap.add_argument("--workers", type=int, default=6, help="동시 호출 수")
    args = ap.parse_args()

    cases = json.loads(Path(args.src).read_text(encoding="utf-8"))
    if args.limit:
        cases = cases[: args.limit]

    started = time.time()
    done = 0
    records: list[dict] = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for record in pool.map(run_one, cases):
            records.append(record)
            done += 1
            if done % 10 == 0 or done == len(cases):
                elapsed = int(time.time() - started)
                print(f"  {done}/{len(cases)}  {elapsed}s", flush=True)

    out = Path(args.dst)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps({"records": records}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    drops = [d for r in records for d in r.get("diag", {}).get("dropped_detail", [])]
    kept = sum(len(r.get("claims") or []) for r in records)
    by_reason: dict[str, int] = {}
    for d in drops:
        by_reason[d["reason"]] = by_reason.get(d["reason"], 0) + 1
    print(f"완료 {len(records)}건 / {int(time.time() - started)}s → {out}")
    print(f"  살아남은 문장  {kept}")
    print(f"  버린 문장      {len(drops)}/{kept + len(drops)}  {by_reason or '{}'}")
    print(f"  503 묶음       {sum(1 for r in records if r.get('status') == 503)}"
          f"  · no_content {sum(1 for r in records if r.get('no_content'))}")


if __name__ == "__main__":
    main()
