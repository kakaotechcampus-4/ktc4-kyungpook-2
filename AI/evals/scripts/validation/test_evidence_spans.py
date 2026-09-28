# AI/evals/scripts/validation/test_evidence_spans.py
"""
근거 구간이 겹쳐서 나가지 않는지 확인하는 단위 테스트.

구조적 정규식과 모델 인용이 같은 곳을 가리키면 구간이 그대로 쌓여서,
화면에서 같은 자리가 두 번 칠해진다. 2026-09-28 서버 스모크에서 실제로
나왔다 — "010-1234-5678" 과 "어머니 연락처는 010-1234-5678 이다." 가
함께 실렸다.

test_no_subject.py 와 같이 모델을 부르지 않는 결정적 규칙 검증이라
홀드아웃 절차가 필요 없다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from validation.nodes import _narrowest_spans


def spans(*pairs):
    return [{"start": start, "end": end} for start, end in pairs]


CASES = [
    # (이름, 입력, 기대)
    ("포함 관계 — 좁은 쪽만 남는다", spans((23, 36), (14, 40)), spans((23, 36))),
    ("완전 중복", spans((5, 10), (5, 10)), spans((5, 10))),
    ("3중 포함 — 가장 좁은 것만", spans((0, 50), (10, 40), (20, 30)), spans((20, 30))),
    ("시작이 같으면 짧은 쪽", spans((0, 10), (0, 5)), spans((0, 5))),
    ("끝이 같으면 늦게 시작한 쪽", spans((0, 10), (5, 10)), spans((5, 10))),
    ("겹치지 않으면 둘 다 — start 순으로", spans((10, 15), (0, 5)), spans((0, 5), (10, 15))),
    ("부분 겹침은 포함이 아니므로 둘 다", spans((0, 10), (5, 15)), spans((0, 10), (5, 15))),
    ("빈 입력", [], []),
]


def run():
    passed = 0
    failed = []

    for name, given, expected in CASES:
        actual = _narrowest_spans(given)
        if actual == expected:
            passed += 1
            print(f"[PASS] {name}")
        else:
            failed.append((name, expected, actual))
            print(f"[FAIL] {name}: 기대={expected} 실제={actual}")

    print()
    print(f"결과: {passed}/{len(CASES)} 통과")
    if failed:
        print("실패 목록:")
        for name, expected, actual in failed:
            print(f"  {name}: 기대={expected}, 실제={actual}")
        sys.exit(1)


if __name__ == "__main__":
    run()
