# AI/evals/scripts/matching/test_criteria_sync.py
"""
판정 기준 문서와 config.py 가 어긋나지 않는지 검사한다. LLM 을 부르지 않는다.

    python evals/scripts/matching/test_criteria_sync.py

검증 에이전트의 test_prompt_sync.py 와 같은 생각이다. 기준이 사람 머릿속에만
있으면 문서가 조용히 낡는다. 문서를 고치고 코드를 안 고치면(또는 반대면) 여기서
깨지게 묶어둔다.

여기서 재는 것은 **문서가 말하는 숫자·플래그와 config.py 의 값이 같은가** 뿐이다.
판정이 옳은지는 채점 스크립트가 본다.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from matching import config  # noqa: E402

CRITERIA = Path(__file__).resolve().parent.parent.parent.parent / "matching" / "CRITERIA.md"


def check_doc_exists():
    if not CRITERIA.exists():
        return [f"판정 기준 문서가 없다: {CRITERIA}"]
    return []


def check_statuses(text):
    """문서의 상태 표에 네 가지가 모두 있는지."""
    missing = [s for s in ("auto", "review", "multi", "unmatched") if f"`{s}`" not in text]
    return [f"상태 표에 {m} 가 없다" for m in missing]


def check_numbers(text):
    """문서가 적은 숫자가 config 와 같은지."""
    problems = []

    # 퍼지 유사도 하한
    if "0.6 이상" not in text:
        problems.append("퍼지 유사도 하한(0.6)이 문서에 없다")
    elif config.FUZZY_MIN_RATIO != 0.6:
        problems.append(f"문서는 퍼지 하한 0.6 인데 config 는 {config.FUZZY_MIN_RATIO}")

    # 한글 3글자 한 글자 차이 = 0.667
    if "0.667" not in text:
        problems.append("3글자 한 글자 차이 유사도(0.667)가 문서에 없다")

    return problems


def check_flags(text):
    """문서가 전제하는 동작이 config 에서 켜져 있는지."""
    rules = [
        ("AUTO_GATE", "structural", "모델 confidence 를 보지 않는다"),
        ("REQUIRE_EXACT_NAME_BOUNDARY", True, "정확 일치에 이름 경계를 요구한다"),
        ("FUZZY_REQUIRE_PARTICLE", True, "오타는 뒤에 조사가 붙어야 인정한다"),
        ("MATCH_GIVEN_NAME", True, "성을 뗀 이름을 따로 본다"),
        ("UNMATCHED_WITHOUT_ANCHOR", True, "근거가 없으면 모델에게 묻지 않는다"),
        ("UNMATCHED_WHEN_HINT_NOT_IN_ROSTER", True, "표지가 명부에 없으면 미등록으로 본다"),
        ("SPLIT_SAME_NAME_CANDIDATES", True, "동명이인은 둘 다 후보로 남긴다"),
        ("ALLOW_AUTO_ON_HINT_ONLY", True, "표지만으로도 자동 확정을 허용한다"),
        ("IGNORE_UNGROUNDED_LLM_PICK", True, "근거 없는 모델 선택은 무시한다"),
    ]
    problems = []
    for name, expected, why in rules:
        actual = getattr(config, name, None)
        if actual != expected:
            problems.append(f"{name} 가 {actual} 다. 문서는 '{why}' 를 전제한다 (기대 {expected})")
    return problems


def check_known_limits(text):
    """홀드아웃에서 확인된 한계가 문서에 남아 있는지."""
    limits = [
        ("쉼표", "이름 뒤 쉼표로 퍼지가 거부되는 한계"),
        ("이름만", "성 없이 이름만 쓰면 auto 가 안 되는 한계"),
        ("독립적인 자기 동작", "조연을 대등 언급으로 잘못 보는 한계"),
    ]
    return [f"알려진 한계가 문서에서 사라졌다 — {why}" for key, why in limits if key not in text]


def main():
    problems = check_doc_exists()
    if problems:
        print("\n".join(f"  ✗ {p}" for p in problems))
        sys.exit(1)

    text = CRITERIA.read_text(encoding="utf-8")
    for fn in (check_statuses, check_numbers, check_flags, check_known_limits):
        problems += fn(text)

    print(f"■ 판정 기준 ↔ config.py 대조 — {CRITERIA.name}")
    if problems:
        print(f"\n  문제 {len(problems)}건")
        for p in problems:
            print(f"  ✗ {p}")
        print("\n  문서를 먼저 고치고 config 를 맞춘다. 순서가 반대면 기준이 코드를 따라간다.")
        sys.exit(1)
    print("  어긋난 곳 없음 ✅")


if __name__ == "__main__":
    main()
