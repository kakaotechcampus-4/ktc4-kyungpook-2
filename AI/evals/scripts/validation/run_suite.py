from dotenv import load_dotenv
from pathlib import Path
load_dotenv(Path(__file__).parent.parent.parent.parent / ".env")

import argparse
import json
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from validation.graph import build_graph

GENERATED_DIR = Path(__file__).parent.parent.parent / "generated" / "validation"
RUNS_DIR = GENERATED_DIR / "runs"
STATE_KEYS = ("content", "subject_name", "subject_child_id", "journal_entry_id")


def load_cases(name: str, limit: int | None):
    with open(GENERATED_DIR / name, encoding="utf-8") as f:
        cases = json.load(f)
    if name == "validation_inputs.json":
        cases = [c for c in cases if c["dataset"] == "dev"]
    return cases[:limit] if limit else cases


def run_one(case, graph):
    state = {k: case.get(k) for k in STATE_KEYS}
    try:
        s = graph.invoke(state)
        if s.get("llm_error"):
            return case["case_id"], None, "LLM 호출 실패 (llm_error)"
        return case["case_id"], {
            "verdict": s["verdict"],
            "issue_types": s["issue_types"],
            "llm_issues": s.get("llm_issues", []),
            "structural_pii_hits": s.get("structural_pii_hits", []),
        }, None
    except Exception as e:
        return case["case_id"], None, str(e)


def diagnose(case, out) -> list[str]:
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

    error_msg = dict(errors)
    missing = [c["case_id"] for c in cases if c["case_id"] not in outputs]

    by_group = defaultdict(Counter)
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

    regressions, fixed = [], []
    if prev_data is not None and not args.rescore:
        prev = set(prev_data["passed_ids"])
        now_failed = {c["case_id"] for _, c, _, _ in failures}
        regressions = sorted(prev & now_failed)
        fixed = sorted(passed_ids - prev)
    save_outputs, save_passed = outputs, passed_ids
    if prev_data is not None and (args.limit or missing):
        ran = set(outputs)
        save_outputs = {**prev_data["outputs"], **outputs}
        save_passed = (set(prev_data["passed_ids"]) - ran) | passed_ids
    latest_path.write_text(json.dumps(
        {"passed_ids": sorted(save_passed), "outputs": save_outputs}, ensure_ascii=False, indent=2), encoding="utf-8")

    order = {"치명적": 0, "준치명적": 1, "등급하락": 2, "과탐": 3, "": 4}
    failures.sort(key=lambda f: order[f[0]])
    total = sum(g["total"] for g in by_group.values())
    ok = sum(g["ok"] for g in by_group.values())
    vok = sum(g["verdict_ok"] for g in by_group.values())

    lines = [f"# {run_name} 결과", "",
             f"- 판정 정답 {vok}/{total} · 유형까지 정답 {ok}/{total} · 실행 오류·누락 **{len(missing)}**건",
             f"- 치명적(BLOCK→PASS) **{sev['치명적']}** · 준치명적(REVIEW→PASS) **{sev['준치명적']}** "
             f"· 등급하락(BLOCK→REVIEW) {sev['등급하락']} · 과탐 {sev['과탐']}",
             f"- 원인: " + (", ".join(f"{k} {v}" for k, v in reason_count.most_common()) or "없음"),
             f"- 회귀(지난번 정답 → 이번 오답): **{len(regressions)}**건, 새로 맞힌 것: {len(fixed)}건",
             "", "| 그룹 | 판정 정답 | 유형까지 정답 |", "|---|---|---|"]
    for g, cnt in sorted(by_group.items()):
        t = cnt["total"]
        lines.append(f"| {g} | {cnt['verdict_ok']}/{t} ({cnt['verdict_ok'] / t * 100:.0f}%) "
                     f"| {cnt['ok']}/{t} ({cnt['ok'] / t * 100:.0f}%) |")
    if missing:
        lines += ["", "## 실행 오류·누락 (채점에서 빠짐)", ""] + [
            f"- {cid}: {error_msg.get(cid, '지난 실행 결과에 없음')}" for cid in missing]
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

    print("\n".join(lines[:5 + len(by_group) + 4]))
    for s, c, out, reasons in failures[:10]:
        print(f"  [{s or '유형'}] {c['case_id']} 원인: {', '.join(reasons)}\n      {c['content'][:80]}")
    if len(failures) > 10:
        print(f"  … 외 {len(failures) - 10}건 → 리포트 참고")
    print(f"\n리포트: {RUNS_DIR / f'{run_name}_report.md'}")

    if missing:
        print(f"\n실행 오류·누락 {len(missing)}건 — 평가를 끝까지 못 돌렸으므로 실패로 종료")
    sys.exit(1 if sev["치명적"] or regressions or missing else 0)


if __name__ == "__main__":
    main()
