"""
holdout 200건 중 이슈 유형이 있는 케이스(BLOCK/REVIEW)만 30%씩 뽑아서
순환 검증 방지용 재작성 대상을 고른다.

PASS 문장은 재작성 대상에서 뺀다 — "정상 문장을 정상이라고 맞히는지"는
판정 로직이 뭔가를 "잡아내야" 하는 상황이 아니므로, 만든 사람이 같아도
순환 검증 문제가 생기지 않는다.
"""
import json
import random
from pathlib import Path

random.seed(99)

with open(Path(__file__).parent.parent.parent / "generated" / "validation" / "validation_inputs.json", encoding="utf-8") as f:
    inputs = json.load(f)

holdout = [c for c in inputs if c["dataset"] == "holdout" and c["expected_verdict"] != "PASS"]

by_type = {}
for c in holdout:
    key = tuple(c["expected_issue_types"])
    by_type.setdefault(key, []).append(c)

selected = []
for key, cases in by_type.items():
    n = max(1, round(len(cases) * 0.3))
    selected.extend(random.sample(cases, min(n, len(cases))))

print(f"선정된 재작성 대상: {len(selected)}건")
for c in selected:
    print(c["case_id"], c["expected_verdict"], c["expected_issue_types"])