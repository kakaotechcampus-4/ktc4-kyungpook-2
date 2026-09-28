# AI/evals/scripts/validation/test_no_subject.py
"""
subject_name이 없을 때(subject_known=False) 코드가 항상 REVIEW(또는
구조적 BLOCK)로 보내는지 확인하는 단위 테스트.

이건 모델 판단력을 재는 eval이 아니라, 코드 규칙이 지켜지는지 보는
pytest 스타일 테스트다. 그래서 홀드아웃/블라인드 절차가 필요 없다 —
"이 사람이 만들어서 편향됐다"는 걱정이 성립하지 않는, 결정적 규칙 검증이다.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from dotenv import load_dotenv
load_dotenv(Path(__file__).parent.parent.parent.parent / ".env")

from validation.graph import build_graph


def load_cases():
    path = Path(__file__).parent.parent.parent / "manifests" / "no_subject_cases.json"
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def run():
    graph = build_graph()
    cases = load_cases()

    passed = 0
    failed = []

    for case in cases:
        state = {
            "content": case["content"],
            "subject_name": case["subject_name"],
        }
        result = graph.invoke(state)
        actual = result["verdict"]
        expected = case["expected_verdict"]

        if actual == expected:
            passed += 1
            print(f"[PASS] {case['case_id']}: {actual}")
        else:
            failed.append((case["case_id"], expected, actual))
            print(f"[FAIL] {case['case_id']}: 기대={expected} 실제={actual}")

    print()
    print(f"결과: {passed}/{len(cases)} 통과")
    if failed:
        print("실패 목록:")
        for case_id, expected, actual in failed:
            print(f"  {case_id}: 기대={expected}, 실제={actual}")


if __name__ == "__main__":
    run()