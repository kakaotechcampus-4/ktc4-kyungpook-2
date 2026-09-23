# AI/evals/scripts/validation/pick_holdout_subset.py
import json, random
from pathlib import Path

random.seed(99)

with open(Path(__file__).parent.parent.parent / "generated" / "validation" / "validation_inputs.json", encoding="utf-8") as f:
    inputs = json.load(f)

holdout = [c for c in inputs if c["dataset"] == "holdout"]

# 이슈 유형별로 30%씩 뽑기 (BLOCK 20건 -> 6건, REVIEW 각 8건 -> 약 2~3건씩)
by_type = {}
for c in holdout:
    key = tuple(c["expected_issue_types"]) or ("PASS",)
    by_type.setdefault(key, []).append(c)

selected = []
for key, cases in by_type.items():
    n = max(1, round(len(cases) * 0.3))
    selected.extend(random.sample(cases, min(n, len(cases))))

print(f"선정된 재작성 대상: {len(selected)}건")
for c in selected:
    print(c["case_id"], c["expected_verdict"], c["expected_issue_types"])