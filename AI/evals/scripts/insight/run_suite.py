# AI/evals/scripts/insight/run_suite.py
"""
인사이트 합성 세트를 여러 번 실행하고 채점한다.

    python evals/scripts/insight/run_suite.py --runs 3 --sample 10
    python evals/scripts/insight/run_suite.py --rescore evals/generated/insight/runs/latest.json --sample 10

모델은 temperature 를 고정할 수 없어 한 번 실행으로 판단하지 않는다 (CRITERIA §9).
실행과 채점을 나눠, 같은 결과를 LLM 없이 다시 채점할 수 있게 한다.
"""

import argparse
import json
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from dotenv import load_dotenv  # noqa: E402

load_dotenv(AI_DIR / ".env")  # config 를 import 하기 전에 읽어야 LUNA_MODEL 이 반영된다

from insight import config  # noqa: E402
from insight.graph import run_insight  # noqa: E402
from insight.schemas import InsightInput  # noqa: E402

GEN = AI_DIR / "evals" / "generated" / "insight"
CASES = GEN / "insight_cases.json"
RUNS = GEN / "runs"


# ── 실행 ────────────────────────────────────────────────────────

def run_one(case: dict, run_no: int) -> dict:
    start = time.time()
    try:
        out = run_insight(InsightInput(**case["input"]))
        insights, error = [i.model_dump() for i in out.insights], None
    except Exception as exc:  # noqa: BLE001 — 한 건이 죽어도 나머지는 돌린다
        insights, error = [], f"{type(exc).__name__}: {exc}"
    return {"case_id": case["case_id"], "category": case["category"], "run": run_no,
            "insights": insights, "error": error, "seconds": round(time.time() - start, 1)}


def execute(runs: int, limit: int, workers: int) -> dict:
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    if limit:
        cases = cases[:limit]
    jobs = [(c, r) for r in range(1, runs + 1) for c in cases]
    print(f"실행: {len(cases)}건 × {runs}회 = {len(jobs)}회 호출 (모델 {config.LUNA_MODEL})")

    with ThreadPoolExecutor(max_workers=workers) as pool:
        records = list(pool.map(lambda job: run_one(*job), jobs))

    result = {"model": config.LUNA_MODEL, "runs": runs, "records": records}
    RUNS.mkdir(parents=True, exist_ok=True)
    path = RUNS / "latest.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"저장: {path}")
    return result


# ── 채점 ────────────────────────────────────────────────────────

def pct(a: int, b: int) -> str:
    return f"{a}/{b} ({a / b * 100:.0f}%)" if b else f"{a}/0"


def finds(insight: dict, planted: set) -> bool:
    """심은 근거와 최소 근거 수 이상 겹치면 찾은 것으로 본다 (CRITERIA §9)."""
    return len(set(insight["claim_ids"]) & planted) >= config.MIN_EVIDENCE_CLAIMS


def score(result: dict, sample: int) -> int:
    cases = {c["case_id"]: c for c in json.loads(CASES.read_text(encoding="utf-8"))}
    records = result["records"]
    errors = [r for r in records if r["error"]]
    ok = [r for r in records if not r["error"]]
    print(f"\n모델 {result['model']} · {result['runs']}회 실행 · 기록 {len(records)}건 · 오류 {len(errors)}건")

    verdicts = defaultdict(list)                              # case_id → 실행별 정답 여부
    tally = {"repeat": [0, 0], "cross": [0, 0], "control": [0, 0]}
    cross_flag, cross_both, leaks = [0, 0], [0, 0], []

    for r in ok:
        case = cases[r["case_id"]]
        exp = case["expected"]
        planted = set(sum((p["claim_ids"] for p in exp["patterns"]), []))

        if exp["empty"]:
            hit = len(r["insights"]) == 0
        else:
            hits = [i for i in r["insights"] if finds(i, planted)]
            hit = bool(hits)
            if case["category"] == "cross" and hits:
                cross_flag[1] += 1
                cross_both[1] += 1
                cross_flag[0] += any(i["cross_institution"] for i in hits)
                cross_both[0] += any("학교" in i["content"] and "센터" in i["content"] for i in hits)

        verdicts[r["case_id"]].append(hit)
        tally[case["category"]][0] += int(hit)
        tally[case["category"]][1] += 1

        for term in exp["forbidden_terms"]:
            for i in r["insights"]:
                if term in i["content"]:
                    leaks.append(f"{r['case_id']} run{r['run']} '{term}'")

    print("\n■ 정답률 (실행 기준)")
    print("  반복 패턴 찾음        ", pct(*tally["repeat"]))
    print("  기관 간 차이 찾음     ", pct(*tally["cross"]))
    print("  대조군 빈 배열        ", pct(*tally["control"]))

    print("\n■ 기관 간 차이 — 찾은 경우")
    print("  cross_institution 참  ", pct(*cross_flag))
    print("  본문에 학교·센터 둘 다", pct(*cross_both))

    print(f"\n■ 다른 아이 이름 노출   {len(leaks)}건", leaks[:5])

    stable = sum(1 for v in verdicts.values() if len(set(v)) == 1)
    print("\n■ 일관성 (모든 실행에서 같은 판정)", pct(stable, len(verdicts)))
    unstable = sorted(k for k, v in verdicts.items() if len(set(v)) > 1)
    if unstable:
        print("  흔들린 케이스:", unstable)

    if ok:
        print(f"\n■ 평균 {sum(r['seconds'] for r in ok) / len(ok):.1f}초/건")

    if errors:
        print("\n■ 오류")
        for r in errors[:5]:
            print(" ", r["case_id"], f"run{r['run']}", r["error"][:100])

    if sample:
        print(f"\n■ 사람 판정용 표본 (최대 {sample}건) — 기록을 넘어선 일반화인지, 요약을 이어 붙였을 뿐인지 본다")
        shown = 0
        for r in ok:
            for i in r["insights"]:
                if shown >= sample:
                    break
                print(f"  [{r['case_id']} {r['category']} run{r['run']}] {i['content']}")
                shown += 1

    return 1 if errors else 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3, help="케이스마다 몇 번 실행할지")
    ap.add_argument("--limit", type=int, default=0, help="앞에서 N건만 (0 = 전체)")
    ap.add_argument("--workers", type=int, default=4, help="동시 호출 수")
    ap.add_argument("--rescore", help="저장된 결과 파일을 LLM 없이 다시 채점")
    ap.add_argument("--sample", type=int, default=0, help="사람 판정용 표본 수")
    args = ap.parse_args()

    if args.rescore:
        result = json.loads(Path(args.rescore).read_text(encoding="utf-8"))
    else:
        result = execute(args.runs, args.limit, args.workers)
    sys.exit(score(result, args.sample))


if __name__ == "__main__":
    main()