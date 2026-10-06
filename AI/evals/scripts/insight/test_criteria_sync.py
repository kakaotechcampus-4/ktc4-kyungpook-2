# AI/evals/scripts/insight/test_criteria_sync.py
"""
CRITERIA.md 와 insight/config.py · prompts.py 가 어긋나지 않았는지 대조한다. LLM 호출 없음.

이름만 비교하면, 플래그를 꺼서 프롬프트에서 규칙이 빠져도 PASS 가 나온다.
그래서 플래그 값과 모델이 실제로 받는 시스템 프롬프트까지 본다.
"""

import re
import sys
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from insight import config  # noqa: E402
from insight.prompts import WRITING_RULES, system_prompt  # noqa: E402

CRITERIA = (AI_DIR / "insight" / "CRITERIA.md").read_text(encoding="utf-8")

#: §4 성립 조건이 전제하는 플래그. 꺼지면 문서와 코드가 다르게 동작한다.
REQUIRED_GATES = ("REQUIRE_VALID_CLAIM_IDS", "REQUIRE_SUPPORT_EVIDENCE", "REQUIRE_SUPPORT_RESULT")


def section(num: int) -> str:
    match = re.search(rf"^## {num}\..*?(?=^## {num + 1}\.)", CRITERIA, re.S | re.M)
    return match.group(0) if match else ""


def main() -> int:
    errors = []

    rows = re.findall(r"^\|\s*`(\w+)`\s*\|\s*([^|]+?)\s*\|", section(5), re.M)
    doc_rules = dict(rows)

    # 1. 이름 집합
    if set(doc_rules) != set(config.WRITING_RULE_FLAGS):
        errors.append(f"§5 플래그 ≠ config: {set(doc_rules) ^ set(config.WRITING_RULE_FLAGS)}")
    if set(doc_rules) != set(WRITING_RULES):
        errors.append(f"§5 플래그 ≠ prompts: {set(doc_rules) ^ set(WRITING_RULES)}")

    # 2. 문장
    for flag, text in doc_rules.items():
        if WRITING_RULES.get(flag) != text:
            errors.append(f"{flag} 문장 불일치\n  문서: {text}\n  코드: {WRITING_RULES.get(flag)}")

    # 3. 플래그 값 — 문서는 전부 켜진 것을 전제한다
    for flag in config.WRITING_RULE_FLAGS:
        if not getattr(config, flag, False):
            errors.append(f"{flag} 가 꺼져 있다. CRITERIA §5 는 켜진 것을 전제한다")
    for flag in REQUIRED_GATES:
        if not getattr(config, flag, False):
            errors.append(f"{flag} 가 꺼져 있다. CRITERIA §4 는 켜진 것을 전제한다")

    # 4. 모델이 실제로 받는 프롬프트에 §5 문장이 전부 들어 있는가
    prompt = system_prompt()
    for flag, text in doc_rules.items():
        if text not in prompt:
            errors.append(f"{flag} 문장이 시스템 프롬프트에 없다")

    # 5. 최소 근거 수
    if f"{config.MIN_EVIDENCE_CLAIMS}개 이상" not in section(4):
        errors.append(f"§4 최소 근거 수가 config({config.MIN_EVIDENCE_CLAIMS})와 다름")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print(
        f"PASS — 쓰는 원칙 {len(doc_rules)}개 · 성립 조건 {len(REQUIRED_GATES)}개 켜짐, "
        f"프롬프트 포함, 최소 근거 {config.MIN_EVIDENCE_CLAIMS}개"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())