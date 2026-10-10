# AI/evals/scripts/summary/test_criteria_sync.py
"""
요약 판정 기준 문서와 config.py 가 어긋나지 않는지 검사한다. LLM 을 부르지 않는다.

    python evals/scripts/summary/test_criteria_sync.py

매칭의 test_criteria_sync.py 와 같은 생각이다. 기준이 사람 머릿속에만 있으면
문서가 조용히 낡는다. 문서를 고치고 코드를 안 고치면(또는 반대면) 여기서 깨진다.

여기서 재는 것은 **문서가 말하는 규칙과 config.py 의 값이 같은가** 뿐이다.
요약이 좋은지는 평가 스크립트가 본다.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from summary import config  # noqa: E402

CRITERIA = Path(__file__).resolve().parent.parent.parent.parent / "summary" / "CRITERIA.md"


def check_doc_exists():
    return [] if CRITERIA.exists() else [f"판정 기준 문서가 없다: {CRITERIA}"]


def check_flags(text):
    """문서가 전제하는 동작이 config 에서 켜져 있는지."""
    rules = [
        ("DROP_UNFOUND_QUOTES", True, "원문에 없는 인용은 버린다"),
        ("DROP_CLAIMS_WITHOUT_EVIDENCE", True, "근거 0 개가 된 문장은 버린다"),
        ("CHECK_PROPER_NOUNS", True, "고유명사가 근거에 있는지 확인한다"),
        ("CONTENT_FROM_CLAIMS_ONLY", True, "본문은 claims 로만 만든다"),
        ("COVERAGE_FROM_EVIDENCE", True, "반영된 일지는 코드가 센다"),
        ("KEEP_HEDGES", True, "추측은 추측으로 남긴다"),
        ("DROP_SENTIMENT_KEEP_FACT", True, "감정은 빼되 사실은 남긴다"),
        ("ANONYMIZE_OTHER_NAMES", True, "요약 대상이 아닌 사람의 이름은 쓰지 않는다"),
        ("REVIEW_ON_OTHER_NAMES", True, "이름이 남았으면 사람이 보게 표시한다"),
        ("FIXED_LENGTH", False, "길이를 고정하지 않는다"),
    ]
    problems = []
    for name, expected, why in rules:
        actual = getattr(config, name, None)
        if actual != expected:
            problems.append(
                f"{name} 가 {actual} 다. 문서는 '{why}' 를 전제한다 (기대 {expected})"
            )
        if name not in text:
            problems.append(f"{name} 가 문서에 없다 — '{why}'")
    return problems


def check_no_threshold(text):
    """
    요약에는 판정 임계값이 없다. 생기면 문서가 먼저 말해야 한다.

    LUNA_* 는 접속 설정이라 세지 않는다 — 판정 기준이 아니다.
    """
    numeric = [
        n
        for n in dir(config)
        if not n.startswith("_")
        and not n.startswith("LUNA_")
        and isinstance(getattr(config, n), (int, float))
        and not isinstance(getattr(config, n), bool)
    ]
    return [
        f"config 에 숫자 상수 {n} 이 생겼다. 임계값을 두려면 CRITERIA.md §0 을 먼저 고친다"
        for n in numeric
    ]


def check_decisions(text):
    """합의로 정해진 것이 문서에서 사라지지 않았는지."""
    decided = [
        ("아동 × 날짜 × 기관", "묶음 단위 — 기관을 가로지르지 않는다"),
        ("인사이트가 가로지른다", "기관 간 비교는 요약이 아니라 인사이트 몫이다"),
        ("spans_for_quotes", "좌표는 모델이 주지 않는다"),
        ("03:00", "날짜 마감 시각"),
        ("debounce 30분", "과거 날짜 일지 대기"),
        ("revision + 1", "승인 후에는 새 판을 만든다"),
        ("교사 작성", "교사가 고친 문장은 근거 없음으로 표시"),
        ("자기충족", "검증 통과율로 요약 품질을 재지 않는다"),
        ("바뀐 문장만 검증한다", "교사가 고친 문장은 검증을 거치지 않았다"),
        ("인사이트는 근거가 있는 문장만", "추적되지 않는 내용은 밖으로 안 나간다"),
        ("가명을 쓰지 않는다", "가명은 거짓 정보가 되고 원문 대조를 끊는다"),
        ("날짜의 숫자는 관찰의 숫자가 아니다", "날짜 숫자가 횟수 근거가 되면 안 된다"),
        ("빈 요약을 내보내지 않는다", "전부 걸러지면 200 이 아니라 503 이다"),
        ("남았으면 사람이 보게 표시한다", "막지 않고 Gate 1 에서 교사가 정한다"),
        ("명부 안팎을 구분하지 않는다", "익명화 수준을 나눌수록 식별성이 올라간다"),
    ]
    return [f"합의된 내용이 문서에서 사라졌다 — {why}" for key, why in decided if key not in text]


def main():
    problems = check_doc_exists()
    if problems:
        print("\n".join(f"  ✗ {p}" for p in problems))
        sys.exit(1)

    text = CRITERIA.read_text(encoding="utf-8")
    for fn in (check_flags, check_no_threshold, check_decisions):
        problems += fn(text)

    print(f"■ 요약 판정 기준 ↔ config.py 대조 — {CRITERIA.name}")
    if problems:
        print(f"\n  문제 {len(problems)}건")
        for p in problems:
            print(f"  ✗ {p}")
        print("\n  문서를 먼저 고치고 config 를 맞춘다. 순서가 반대면 기준이 코드를 따라간다.")
        sys.exit(1)
    print("  어긋난 곳 없음 ✅")


if __name__ == "__main__":
    main()
