# AI/evals/scripts/insight/test_criteria_sync.py
"""CRITERIA.md 와 insight/config.py · prompts.py 가 어긋나지 않았는지 대조한다. LLM 호출 없음."""

import re
import sys
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from insight import config  # noqa: E402
from insight.prompts import WRITING_RULES  # noqa: E402

CRITERIA = (AI_DIR / "insight" / "CRITERIA.md").read_text(encoding="utf-8")


def section(num: int) -> str:
    match = re.search(rf"^## {num}\..*?(?=^## {num + 1}\.)", CRITERIA, re.S | re.M)
    return match.group(0) if match else ""


def main() -> int:
    errors = []

    rows = re.findall(r"^\|\s*`(\w+)`\s*\|\s*([^|]+?)\s*\|", section(5), re.M)
    doc_rules = dict(rows)

    if set(doc_rules) != set(config.WRITING_RULE_FLAGS):
        errors.append(f"§5 플래그 ≠ config: {set(doc_rules) ^ set(config.WRITING_RULE_FLAGS)}")
    if set(doc_rules) != set(WRITING_RULES):
        errors.append(f"§5 플래그 ≠ prompts: {set(doc_rules) ^ set(WRITING_RULES)}")
    for flag, text in doc_rules.items():
        if WRITING_RULES.get(flag) != text:
            errors.append(f"{flag} 문장 불일치\n  문서: {text}\n  코드: {WRITING_RULES.get(flag)}")

    if f"{config.MIN_EVIDENCE_CLAIMS}개 이상" not in section(4):
        errors.append(f"§4 최소 근거 수가 config({config.MIN_EVIDENCE_CLAIMS})와 다름")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print(f"PASS — 쓰는 원칙 {len(doc_rules)}개, 최소 근거 {config.MIN_EVIDENCE_CLAIMS}개 일치")
    return 0


if __name__ == "__main__":
    sys.exit(main())