import argparse
import json
import sys
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from dotenv import load_dotenv  # noqa: E402

load_dotenv(AI_DIR / ".env")

from common.luna import model as luna_model
from insight import config  # noqa: E402
from insight.graph import run_insight  # noqa: E402
from insight.schemas import InsightInput  # noqa: E402

GEN = AI_DIR / "evals" / "generated" / "insight"
DEFAULT_CASES = GEN / "insight_cases.json"
RUNS = GEN / "runs"


def load_cases(path) -> list[dict]:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def run_one(case: dict, run_no: int) -> dict:
    start = time.time()
    try:
        out = run_insight(InsightInput(**case["input"]))
        insights, error = [i.model_dump() for i in out.insights], None
    except Exception as exc:  # noqa: BLE001 — 한 건이 죽어도 나머지는 돌린다
        insights, error = [], f"{type(exc).__name__}: {exc}"
    return {"case_id": case["case_id"], "category": case["category"], "run": run_no,
            "insights": insights, "error": error, "seconds": round(time.time() - start, 1)}


def execute(cases_path: Path, runs: int, limit: int, workers: int, name: str) -> dict:
    cases = load_cases(cases_path)
    if limit:
        cases = cases[:limit]
    jobs = [(c, r) for r in range(1, runs + 1) for c in cases]
    print(f"실행: {len(cases)}건 × {runs}회 = {len(jobs)}회 호출 (모델 {luna_model('insight')})")

    with ThreadPoolExecutor(max_workers=workers) as pool:
        records = list(pool.map(lambda job: run_one(*job), jobs))

    result = {"model": luna_model("insight"), "cases": str(Path(cases_path).resolve()),
              "runs": runs, "records": records}
    RUNS.mkdir(parents=True, exist_ok=True)
    path = RUNS / f"{name}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"저장: {path}")
    return result


def pct(a: int, b: int) -> str:
    return f"{a}/{b} ({a / b * 100:.0f}%)" if b else f"{a}/0"


def finds(insight: dict, pattern_ids: list[str]) -> bool:
    return len(set(insight["claim_ids"]) & set(pattern_ids)) >= config.MIN_EVIDENCE_CLAIMS


def spans_institutions(insight: dict, pattern_ids: list[str], types_of: dict) -> bool:
    shared = set(insight["claim_ids"]) & set(pattern_ids)
    return len({types_of[c] for c in shared}) >= 2


def score(result: dict, sample: int) -> int:
    cases_path = result.get("cases", str(DEFAULT_CASES))
    cases = {c["case_id"]: c for c in load_cases(cases_path)}
    records = result["records"]
    errors = [r for r in records if r["error"]]
    ok = [r for r in records if not r["error"]]
    print(f"\n모델 {result['model']} · 세트 {Path(cases_path).name} · {result['runs']}회 실행"
          f" · 기록 {len(records)}건 · 오류 {len(errors)}건")

    stat = defaultdict(lambda: {"records": 0, "correct": 0, "patterns": 0, "found": 0,
                                "insights": 0, "extra": 0})
    verdicts = defaultdict(list)
    cross_mention, cross_flag, leaks = [0, 0], [0, 0], []
    split = [0, 0]

    for r in ok:
        case = cases[r["case_id"]]
        exp = case["expected"]
        insights = r["insights"]
        types_of = {c["claim_id"]: c["institution_type"] for c in case["input"]["claims"]}

        used, found = set(), 0
        for p in exp["patterns"]:
            hits = [k for k, i in enumerate(insights) if finds(i, p["claim_ids"])]
            if p.get("cross_institution"):
                split[1] += 1
                whole = [k for k in hits if spans_institutions(insights[k], p["claim_ids"], types_of)]
                split[0] += int(not whole and len(hits) >= 2)
                hits = whole
            if not hits:
                continue
            found += 1
            used.update(hits)
            if p.get("cross_institution"):
                needed = {types_of[c] for c in p["claim_ids"]}
                cross_mention[1] += 1
                cross_flag[1] += 1
                cross_mention[0] += any(all(t in insights[k]["content"] for t in needed) for k in hits)
                cross_flag[0] += any(insights[k]["cross_institution"] for k in hits)

        extra = len(insights) - len(used)
        correct = found == len(exp["patterns"]) and extra == 0

        s = stat[case["category"]]
        s["records"] += 1
        s["correct"] += int(correct)
        s["patterns"] += len(exp["patterns"])
        s["found"] += found
        s["insights"] += len(insights)
        s["extra"] += extra
        verdicts[(case["category"], r["case_id"])].append(correct)

        for term in exp.get("forbidden_terms", []):
            for i in insights:
                if term in i["content"]:
                    leaks.append(f"{r['case_id']} run{r['run']} '{term}'")

    print("\n■ 갈래별 (실행 기준)")
    print(f"  {'갈래':<9} {'완전 정답':<14} {'패턴 찾음':<14} {'남는 인사이트':<16} 일관성")
    for cat in sorted(stat):
        s = stat[cat]
        cases_in = [v for (c, _), v in verdicts.items() if c == cat]
        stable = sum(1 for v in cases_in if len(set(v)) == 1)
        print(f"  {cat:<9} {pct(s['correct'], s['records']):<14} {pct(s['found'], s['patterns']):<14} "
              f"{pct(s['extra'], s['insights']):<16} {pct(stable, len(cases_in))}")

    print("\n■ 기관 간 차이 패턴 — 찾은 경우")
    print("  본문에 근거 기관 종류가 모두 나옴", pct(*cross_mention))
    print("  cross_institution 참            ", pct(*cross_flag))
    print("  기관별로 쪼갬 (전체 패턴 기준)    ", pct(*split), " ← 0 이어야 함")

    all_insights = [i for r in ok for i in r["insights"]]
    tagged = [i for i in all_insights if i.get("relevant_institution_types")]
    all_types = set(config.INSTITUTION_TYPES)
    every = [i for i in tagged if set(i["relevant_institution_types"]) == all_types]
    beyond = [i for i in tagged if set(i["relevant_institution_types"]) - set(i.get("institution_types", []))]
    no_reason = [i for i in tagged if not i.get("relevance_reason")]
    type_count = Counter(t for i in tagged for t in i["relevant_institution_types"])

    print("\n■ 쓸모 태그 (정답 없음 — 신호가 되는지 본다)")
    print("  태그 붙은 인사이트        ", pct(len(tagged), len(all_insights)))
    print("  세 종류 모두 태그         ", pct(len(every), len(tagged)), " ← 높으면 신호가 안 됨")
    print("  근거 기관 밖 태그 포함     ", pct(len(beyond), len(tagged)), " ← 새로 알려줄 기관")
    print("  태그는 있는데 이유 없음   ", pct(len(no_reason), len(tagged)))
    print("  종류별                    ", dict(type_count))

    print(f"\n■ 금지어 노출 {len(leaks)}건", leaks[:5])

    unstable = sorted(cid for (_, cid), v in verdicts.items() if len(set(v)) > 1)
    if unstable:
        print("\n■ 흔들린 케이스:", unstable)

    if ok:
        print(f"\n■ 평균 {sum(r['seconds'] for r in ok) / len(ok):.1f}초/건")

    if errors:
        print("\n■ 오류")
        for r in errors[:5]:
            print(" ", r["case_id"], f"run{r['run']}", r["error"][:100])

    if sample:
        print(f"\n■ 사람 판정용 표본 (최대 {sample}건) — 일반화·이어 붙이기인지, 쓸모 태그가 상황에 맞는지 본다")
        shown = 0
        for r in ok:
            for i in r["insights"]:
                if shown >= sample:
                    break
                print(f"  [{r['case_id']} {r['category']} run{r['run']}] {i['content']}")
                print(f"      근거 기관 {i.get('institution_types', [])} → 쓸모 {i.get('relevant_institution_types', [])}"
                      f" | {i.get('relevance_reason', '')}")
                shown += 1

    return 1 if errors else 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default=str(DEFAULT_CASES), help="세트 파일 (기본: 합성 세트)")
    ap.add_argument("--runs", type=int, default=3, help="케이스마다 몇 번 실행할지")
    ap.add_argument("--limit", type=int, default=0, help="앞에서 N건만 (0 = 전체)")
    ap.add_argument("--workers", type=int, default=4, help="동시 호출 수")
    ap.add_argument("--name", default="latest", help="결과 파일 이름 (runs/<name>.json)")
    ap.add_argument("--rescore", help="저장된 결과 파일을 LLM 없이 다시 채점")
    ap.add_argument("--sample", type=int, default=0, help="사람 판정용 표본 수")
    ap.add_argument("--weak-mode", choices=["off", "drop", "refine"], default=None,
                    help="약한 인사이트 처리 (INSIGHT_WEAK_MODE). 안 주면 config 값")
    args = ap.parse_args()
    if args.weak_mode:
        config.INSIGHT_WEAK_MODE = args.weak_mode
    print(f"[suite] INSIGHT_WEAK_MODE={config.INSIGHT_WEAK_MODE}")

    if args.rescore:
        result = json.loads(Path(args.rescore).read_text(encoding="utf-8"))
    else:
        result = execute(Path(args.cases), args.runs, args.limit, args.workers, args.name)
    sys.exit(score(result, args.sample))


if __name__ == "__main__":
    main()