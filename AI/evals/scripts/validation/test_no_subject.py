import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from dotenv import load_dotenv
load_dotenv(Path(__file__).parent.parent.parent.parent / ".env")

from validation.graph import build_graph


def load_cases():
    path = Path(__file__).parent.parent.parent / "fixtures" / "no_subject_cases.json"
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