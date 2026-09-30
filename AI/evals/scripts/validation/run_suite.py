"""
케이스 파일 하나를 받아 실행 → 채점 → 틀린 이유 자동 분류 → 지난번 대비 회귀까지 한 번에.

지금까지는 틀린 케이스가 나오면 디버그 스크립트를 따로 짜서 llm_issues를 들여다봤다.
이 스크립트는 그걸 매번 자동으로 한다. 틀린 케이스마다 원인을 넷 중 하나로 붙인다.

    미감지     모델이 그 유형을 아예 못 봄 (llm_issues에 없음)       → 유형 정의 문제
    귀속필터   봤는데 attributed_to_subject=false 로 버려짐          → 귀속 규칙 문제
    인용실패   봤고 귀속도 됐는데 최종에 없음 (원문에서 인용 못 찾음)  → 근거 추출 문제
    등급       유형은 다 맞췄는데 verdict 가 다름                     → config 매핑 문제
    과탐       기대하지 않은 유형을 잡음                               → 정의가 너무 넓음

실행 (AI 폴더에서):
    python3 evals/scripts/validation/run_suite.py                              # dev 전체
    python3 evals/scripts/validation/run_suite.py --cases metamorphic_cases.json
    python3 evals/scripts/validation/run_suite.py --cases metamorphic_cases.json --limit 20
    python3 evals/scripts/validation/run_suite.py --cases metamorphic_cases.json --rescore
        (--rescore: LLM 을 다시 부르지 않고 지난 실행 결과로 채점만 다시 한다. 비용 0)

결과:
    generated/validation/runs/<이름>_latest.json   이번 실행 결과 (다음 실행의 비교 기준)
    generated/validation/runs/<이름>_report.md     사람이 읽는 리포트
"""
from dotenv import load_dotenv
from pathlib import Path
load_dotenv(Path(__file__).parent.parent.parent.parent / ".env")

import argparse
import json
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))  # AI/

from validation.graph import build_graph

GENERATED_DIR = Path(__file__).parent.parent.parent / "generated" / "validation"
RUNS_DIR = GENERATED_DIR / "runs"
STATE_KEYS = ("content", "subject_name", "subject_child_id", "journal_entry_id")


def load_cases(name: str, limit: int | None):
    with open(GENERATED_DIR / name, encoding="utf-8") as f:
        cases = json.load(f)
    if name == "validation_inputs.json":
        cases = [c for c in cases if c["dataset"] == "dev"]  # holdout 은 여기서 절대 안 돈다
    return cases[:limit] if limit else cases


def run_one(case, graph):
    state = {k: case.get(k) for k in STATE_KEYS}
    try:
        s = graph.invoke(state)
        return case["case_id"], {
            "verdict": s["verdict"],
            "issue_types": s["issue_types"],
            "llm_issues": s.get("llm_issues", []),
            "structural_pii_hits": s.get("structural_pii_hits", []),
        }, None
    except Exception as e:
        return case["case_id"], None, str(e)


def diagnose(case, out) -> list[str]:
    """틀린 이유 목록. 빈 목록이면 정답."""
    reasons = []
    expected = set(case["expected_issue_types"])
    optional = set(case.get("optional_issue_types", []))
    final = set(out["issue_types"]) - optional
    seen = {i.get("issue_type") for i in out["llm_issues"]}
    attributed = {i.get("issue_type") for i in out["llm_issues"] if i.get("attributed_to_subject")}

    for t in sorted(expected - final):
        if t not in seen:
            reasons.append(f"미감지:{t}")
        elif t not in attributed:
            reasons.append(f"귀속필터:{t}")
        else:
            reasons.append(f"인용실패:{t}")
    for t in sorted(final - expected):
        reasons.append(f"과탐:{t}")
    if not reasons and out["verdict"] != case["expected_verdict"]:
        reasons.append("등급")
    return reasons


def severity(expected, got):
    if expected == "BLOCK" and got == "PASS":
        return "치명적"
    if expected == "REVIEW" and got == "PASS":
        return "준치명적"
    if expected == "BLOCK" and got == "REVIEW":
        return "등급하락"
    if expected == "PASS" and got != "PASS":
        return "과탐"
    return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default="validation_inputs.json", help="generated/validation/ 안의 파일 이름")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--rescore", action="store_true", help="LLM 호출 없이 지난 결과로 다시 채점")
    args = ap.parse_args()

    cases = load_cases(args.cases, args.limit)
    run_name = Path(args.cases).stem
    print(f"실행 대상: {len(cases)}건 ({args.cases}, 동시 {args.workers}개)")

    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    latest_path = RUNS_DIR / f"{run_name}_latest.json"
    prev_data = json.loads(latest_path.read_text(encoding="utf-8")) if latest_path.exists() else None

    outputs, errors = {}, []
    if args.rescore:
        if prev_data is None:
            raise SystemExit("지난 실행 결과가 없음 — --rescore 없이 먼저 한 번 실행할 것")
        outputs = {c["case_id"]: prev_data["outputs"][c["case_id"]]
                   for c in cases if c["case_id"] in prev_data["outputs"]}
        print(f"재채점: 지난 결과 {len(outputs)}건 사용 (LLM 호출 없음)")
    else:
        graph = build_graph()
        with ThreadPoolExecutor(max_workers=args.workers) as ex:
            futures = [ex.submit(run_one, c, graph) for c in cases]
            for i, fut in enumerate(as_completed(futures), 1):
                cid, out, err = fut.result()
                if out is not None:
                    outputs[cid] = out
                else:
                    errors.append((cid, err))
                if i % 50 == 0 or i == len(cases):
                    print(f"  {i}/{len(cases)} 완료...")

    # ── 채점 ──
    by_group = defaultdict(Counter)       # relation(없으면 expected 유형) 별 정답/전체
    sev = Counter()
    reason_count = Counter()
    failures = []
    passed_ids = set()
    for c in cases:
        out = outputs.get(c["case_id"])
        if out is None:
            continue
        group = c.get("relation") or (",".join(c["expected_issue_types"]) or "PASS")
        reasons = diagnose(c, out)
        ok = not reasons and out["verdict"] == c["expected_verdict"]
        by_group[group]["total"] += 1
        if out["verdict"] == c["expected_verdict"]:
            by_group[group]["verdict_ok"] += 1
        if ok:
            by_group[group]["ok"] += 1
            passed_ids.add(c["case_id"])
            continue
        s = severity(c["expected_verdict"], out["verdict"])
        if s:
            sev[s] += 1
        for r in reasons:
            reason_count[r.split(":")[0]] += 1
        failures.append((s, c, out, reasons))

    # ── 회귀: 지난번엔 맞았는데 이번엔 틀린 것 ──
    regressions, fixed = [], []
    if prev_data is not None and not args.rescore:
        prev = set(prev_data["passed_ids"])
        now_failed = {c["case_id"] for _, c, _, _ in failures}
        regressions = sorted(prev & now_failed)
        fixed = sorted(passed_ids - prev)
    # --limit 으로 일부만 돌렸으면 지난 기록에 덮어쓰지 않고 합친다.
    # (전에는 30건만 돌린 결과가 전체 기준점을 지워버려서 다음 회귀 비교가 30건으로 줄었다)
    save_outputs, save_passed = outputs, passed_ids
    if prev_data is not None and args.limit:
        ran = {c["case_id"] for c in cases}
        save_outputs = {**prev_data["outputs"], **outputs}
        save_passed = (set(prev_data["passed_ids"]) - ran) | passed_ids
    latest_path.write_text(json.dumps(
        {"passed_ids": sorted(save_passed), "outputs": save_outputs}, ensure_ascii=False, indent=2), encoding="utf-8")

    # ── 리포트 ──
    order = {"치명적": 0, "준치명적": 1, "등급하락": 2, "과탐": 3, "": 4}
    failures.sort(key=lambda f: order[f[0]])
    total = sum(g["total"] for g in by_group.values())
    ok = sum(g["ok"] for g in by_group.values())
    vok = sum(g["verdict_ok"] for g in by_group.values())

    lines = [f"# {run_name} 결과", "",
             f"- 판정 정답 {vok}/{total} · 유형까지 정답 {ok}/{total} · 에러 {len(errors)}건",
             f"- 치명적(BLOCK→PASS) **{sev['치명적']}** · 준치명적(REVIEW→PASS) **{sev['준치명적']}** "
             f"· 등급하락(BLOCK→REVIEW) {sev['등급하락']} · 과탐 {sev['과탐']}",
             f"- 원인: " + (", ".join(f"{k} {v}" for k, v in reason_count.most_common()) or "없음"),
             f"- 회귀(지난번 정답 → 이번 오답): **{len(regressions)}**건, 새로 맞힌 것: {len(fixed)}건",
             "", "| 그룹 | 판정 정답 | 유형까지 정답 |", "|---|---|---|"]
    for g, cnt in sorted(by_group.items()):
        t = cnt["total"]
        lines.append(f"| {g} | {cnt['verdict_ok']}/{t} ({cnt['verdict_ok'] / t * 100:.0f}%) "
                     f"| {cnt['ok']}/{t} ({cnt['ok'] / t * 100:.0f}%) |")
    if regressions:
        lines += ["", "## 회귀", ""] + [f"- {cid}" for cid in regressions]
    lines += ["", "## 틀린 케이스", ""]
    for s, c, out, reasons in failures:
        tag = f" `{c['relation']}`" if c.get("relation") else ""
        lines += [f"- **{c['case_id']}**{tag} [{s or '유형'}] 기대 {c['expected_verdict']}{c['expected_issue_types']}"
                  f" → {out['verdict']}{out['issue_types']} · 원인: {', '.join(reasons)}",
                  f"  - 본문: {c['content']}"]
    report = "\n".join(lines)
    (RUNS_DIR / f"{run_name}_report.md").write_text(report, encoding="utf-8")

    # 터미널에는 요약 + 틀린 것 앞 10건만
    print("\n".join(lines[:5 + len(by_group) + 4]))
    for s, c, out, reasons in failures[:10]:
        print(f"  [{s or '유형'}] {c['case_id']} 원인: {', '.join(reasons)}\n      {c['content'][:80]}")
    if len(failures) > 10:
        print(f"  … 외 {len(failures) - 10}건 → 리포트 참고")
    print(f"\n리포트: {RUNS_DIR / f'{run_name}_report.md'}")

    # 치명적 오류나 회귀가 있으면 실패 코드로 끝낸다 → 나중에 CI에 그대로 쓸 수 있음
    sys.exit(1 if sev["치명적"] or regressions else 0)


if __name__ == "__main__":
    main()
