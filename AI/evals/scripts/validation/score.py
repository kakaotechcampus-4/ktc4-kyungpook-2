# AI/evals/scripts/validation/score.py
import json
from pathlib import Path
from collections import Counter


def load_inputs():
    path = Path(__file__).parent.parent.parent / "generated" / "validation" / "validation_inputs.json"
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def score(inputs, outputs_by_case_id, dataset_filter=None):
    confusion = Counter()
    issue_type_hits = Counter()
    issue_type_total = Counter()

    for case in inputs:
        if dataset_filter and case["dataset"] != dataset_filter:
            continue
        output = outputs_by_case_id.get(case["case_id"])
        if output is None:
            continue

        confusion[(case["expected_verdict"], output["verdict"])] += 1

        # 이슈 유형별 정확도도 같이 집계 (BLOCK/REVIEW 세부 유형 맞았는지)
        for issue in case["expected_issue_types"]:
            issue_type_total[issue] += 1
            if issue in output.get("issue_types", []):
                issue_type_hits[issue] += 1

    치명적 = confusion[("BLOCK", "PASS")]
    준치명적 = confusion[("REVIEW", "PASS")]
    block_total = sum(v for k, v in confusion.items() if k[0] == "BLOCK")
    block_recall = confusion[("BLOCK", "BLOCK")] / block_total if block_total else None
    pass_total = sum(v for k, v in confusion.items() if k[0] == "PASS")
    과탐 = confusion[("PASS", "BLOCK")]
    과탐률 = 과탐 / pass_total if pass_total else None

    print("=" * 50)
    print(f"치명적 오류(BLOCK→PASS): {치명적}건  (목표: 0)")
    print(f"준치명적 오류(REVIEW→PASS): {준치명적}건  (목표: ≤2)")
    print(f"BLOCK recall: {block_recall}  (목표: ≥0.95)")
    print(f"과탐률(PASS→BLOCK): {과탐률}  (목표: ≤0.15)")
    print()
    print("이슈 유형별 탐지율:")
    for issue, total in issue_type_total.items():
        hit = issue_type_hits[issue]
        print(f"  {issue}: {hit}/{total} ({hit/total*100:.1f}%)")

    return confusion


if __name__ == "__main__":
    inputs = load_inputs()
    print(f"전체 케이스: {len(inputs)}건")
    print(f"dev: {sum(1 for c in inputs if c['dataset']=='dev')}건, "
          f"holdout: {sum(1 for c in inputs if c['dataset']=='holdout')}건")

    outputs = {}  # mock 단계, 비워서 시작 — 그래프 완성 전까지는 손으로 몇 건 채워서 검증
    score(inputs, outputs, dataset_filter="dev")