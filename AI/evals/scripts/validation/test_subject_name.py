# AI/evals/scripts/validation/test_subject_name.py
"""
판정 대상 아이의 이름을 개인정보표현으로 본 모델 판정을 버리는지 확인하는 단위 테스트.

10/28 로컬 파이프라인에서 "김하늘이 블록을 세 번 쌓았다." 같은 평범한 문장이
돌릴 때마다 BLOCK·PASS 로 흔들렸다. 모델이 주인공 이름을 개인정보로 본 것이다.
이름은 개인정보표현(연락처·주소·생년월일)이 아니므로 그 판정만 버린다.

test_issues.py 와 같이 모델을 부르지 않는 결정적 규칙 검증이다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from validation.nodes import reflect

SUBJECT = "김하늘"


def run(content, quote, issue_type="개인정보표현", pii=None):
    return reflect({
        "content": content,
        "structural_pii_hits": pii or [],
        "subject_known": True,
        "subject_name": SUBJECT,
        "llm_error": False,
        "llm_issues": [{"issue_type": issue_type, "evidence_quote": quote, "attributed_to_subject": True}],
    })


PHONE = "하늘이 어머니 연락처는 010-1234-5678 이다."
PHONE_START = PHONE.index("010")

CASES = [
    # (이름, 결과, 기대 판정)
    ("성+이름만 가리킴 → 버림", run("김하늘이 블록을 세 번 쌓았다.", "김하늘"), "PASS"),
    ("이름+조사 → 버림", run("하늘이는 블록을 세 번 쌓았다.", "하늘이는"), "PASS"),
    ("성+이름+조사 → 버림", run("김하늘이 점심을 다 먹었다.", "김하늘이"), "PASS"),
    ("이름 뒤에 다른 말이 붙음 → 그대로", run("김하늘 어머니 연락처를 받았다.", "김하늘 어머니 연락처"), "BLOCK"),
    ("다른 아이 이름 → 이번 규칙과 무관, 그대로", run("김하늘이 이준호와 놀았다.", "이준호"), "BLOCK"),
    ("진단명은 이름이어도 그대로", run("김하늘이 블록을 쌓았다.", "김하늘", issue_type="진단명"), "BLOCK"),
    ("전화번호는 정규식이 잡음",
     run(PHONE, "하늘이", pii=[{"start": PHONE_START, "end": PHONE_START + 13}]), "BLOCK"),
]


def run_all():
    passed = 0
    failed = []
    for name, result, expected in CASES:
        if result["verdict"] == expected:
            passed += 1
            print(f"[PASS] {name}")
        else:
            failed.append(name)
            print(f"[FAIL] {name}: 기대={expected} 실제={result['verdict']} issues={result['issues']}")
    print()
    print(f"결과: {passed}/{len(CASES)} 통과")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    run_all()
