"""
요약 에이전트를 테스트 데이터 전체에 돌려 결과 파일을 만든다.

    python evals/scripts/summary/run_eval.py <입력.json> <결과.json> [--limit N] [--workers N]

LLM 을 부르므로 시간과 토큰이 든다. 채점은 결과 파일만 있으면 되므로 score.py 로
따로 돌린다 — 지표를 바꿔가며 여러 번 채점할 때 매번 다시 부르지 않기 위해서다.
매칭의 run_eval.py 와 같은 구조다.

그래프를 한 번만 돌리고 계약 출력과 진단값을 같은 실행에서 뽑는다. 두 번 부르면
호출이 두 배가 되고, 비결정성 때문에 진단값이 그 출력과 맞지 않는다.
"""

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

#: 채점에 쓰는 정답 필드. 없으면 그 지표는 건너뛴다.
EXPECTED = (
    #: 반드시 요약에 반영돼야 하는 일지. 빠지면 누락이다.
    "expected_covered_entry_ids",
    #: 빠져도 되는 일지 ("특이사항 없음" 같은 것).
    "expected_droppable_entry_ids",
    #: 요약에 반드시 담겨야 하는 사실. 사람이 읽고 판정한다.
    "expected_facts",
    #: 요약에 나오면 안 되는 문자열 (다른 아이 이름 등). 코드가 본다.
    "expected_absent",
)

PASSTHROUGH = ("case_id", "category", "difficulty", "note")


def _claim_record(claim: dict, by_id: dict) -> dict:
    """채점이 보는 claim 한 건. 인용이 원문의 몇 할인지까지 남긴다."""
    evidence = []
    for e in claim["evidence"]:
        source_len = len(by_id.get(e["journal_entry_id"], ""))
        quote_len = len(e["quote"] or "")
        evidence.append(
            {
                "journal_entry_id": e["journal_entry_id"],
                #: 인용문 자체. 사람이 "문장이 이 인용으로 설명되는가" 를 보려면
                #: 길이가 아니라 글자가 있어야 한다 (score.py --sample).
                "quote": e["quote"],
                "quote_chars": quote_len,
                "source_chars": source_len,
                #: 1.0 이면 일지를 통째로 인용한 것이다. 대조는 통과하지만
                #: Gate 1 에서 하이라이트가 전체라 아무것도 알려주지 못한다.
                "quote_ratio": round(quote_len / source_len, 4) if source_len else None,
            }
        )
    return {
        "text": claim["text"],
        "evidence": evidence,
        #: 근거가 두 일지 이상에 걸쳐 있는가. 요약이 "묶었는가" 를 재는 값이다.
        #: 일지를 그대로 베끼면 이 값이 늘 False 다.
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
            }
        )
        by_id = final["by_id"]
        claims = [_claim_record(c, by_id) for c in final["claims"]]
        record.update(
            content=final["content"],
            claims=claims,
            covered_entry_ids=final["covered_entry_ids"],
            uncovered_entry_ids=final["uncovered_entry_ids"],
            llm_called=final.get("llm_called", False),
            error=final.get("llm_error"),
            diag={
                #: 원문에서 못 찾아 버린 근거 수. 모델이 인용을 다듬은 횟수다.
                "dropped_evidence": final.get("dropped_evidence", 0),
                #: 근거가 0 개가 되거나 숫자가 안 맞아 버린 문장들.
                "dropped_claims": final.get("dropped_claims", []),
                "source_chars": sum(len(v) for v in by_id.values()),
                "content_chars": len(final["content"]),
                "usage": final.get("llm_usage") or {},
            },
        )
    except Exception as exc:  # noqa: BLE001 - 한 건이 죽어도 나머지는 돌려야 한다
        record.update(
            content="", claims=[], covered_entry_ids=[], uncovered_entry_ids=[],
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
    print(f"완료 {len(records)}건 / {int(time.time() - started)}s → {out}")


if __name__ == "__main__":
    main()
