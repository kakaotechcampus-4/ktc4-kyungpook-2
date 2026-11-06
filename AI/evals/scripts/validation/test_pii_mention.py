# AI/evals/scripts/validation/test_pii_mention.py
"""
"연락처로 알렸다" 처럼 값 없는 연락처·주소 언급을 개인정보표현으로 본 모델 판정을 버리는지 확인한다.

11/05 반복 실험: "우진이 어머니 연락처로 하원 시간을 알렸다" 가 10번 중 1~3번,
"우진이 집 주소로 가정통신문을 보냈다" 가 10번 중 8번 BLOCK 됐다. 모델이 "연락처"·"주소"
낱말만 보고 개인정보로 판정한 것이다. 값이 없으면 개인정보가 아니므로 그 판정만 버린다.
값이 적힌 문장은 모델이 앞부분만 짚어도 그대로 BLOCK 이어야 한다 (치명 방지).

모델을 부르지 않는다. test_subject_name.py 와 같은 방식이다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from validation.nodes import reflect

SUBJECT = "김우진"


def run(content, quote, pii=None):
    return reflect({
        "content": content,
        "structural_pii_hits": pii or [],
        "subject_known": True,
        "subject_name": SUBJECT,
        "llm_error": False,
        "llm_issues": [{"issue_type": "개인정보표현", "evidence_quote": quote, "attributed_to_subject": True}],
    })


PHONE = "우진이 어머니 연락처 010-1234-5678 로 하원 시간을 알렸다."
AT = PHONE.index("010")

CASES = [
    # 값 없는 언급 → 모델 판정을 버린다 (11/05 표의 문장들)
    ("연락처로 알렸다", run("우진이 어머니 연락처로 하원 시간을 알렸다.", "우진이 어머니 연락처"), "PASS"),
    ("연락처를 확인해 알렸다", run("우진이 어머니 연락처를 확인해 하원 시간을 알렸다.", "우진이 어머니 연락처"), "PASS"),
    ("집 주소로 보냈다", run("우진이 집 주소로 가정통신문을 보냈다.", "우진이 집 주소"), "PASS"),
    ("이름 없이 '집 주소로'만 짚음", run("우진이 집 주소로 가정통신문을 보냈다.", "집 주소로"), "PASS"),
    ("아버지 연락처로 알렸다", run("우진이 아버지 연락처로 낮잠 시간을 알렸다.", "우진이 아버지 연락처"), "PASS"),
    # 값이 있으면 그대로 BLOCK — 모델이 앞부분만 짚어도
    ("전화번호는 정규식이 잡음", run(PHONE, "우진이 어머니 연락처", pii=[{"start": AT, "end": AT + 13}]), "BLOCK"),
    ("주소 값(숫자)", run("우진이 집 주소는 대구 북구 산격동 12-3 이다.", "우진이 집 주소"), "BLOCK"),
    ("주소 값(숫자 없음)", run("우진이 집 주소는 대구 북구 산격동이다.", "집 주소"), "BLOCK"),
    ("아파트 이름", run("우진이는 범어동 아파트에 산다.", "범어동 아파트"), "BLOCK"),
    ("이메일", run("어머니 이메일 abc@x.com 으로 보냈다.", "어머니 이메일"), "BLOCK"),
    ("생일 (연락처 낱말이 아님)", run("우진이 생일은 11월 21일이다.", "우진이 생일"), "BLOCK"),
    ("다른 아이 연락처 (가족 호칭 아님)", run("하원 전에 친구 연락처를 물어봤다.", "친구 연락처"), "BLOCK"),
    ("원문에 없는 말을 짚음", run("우진이 어머니께 하원 시간을 알렸다.", "어머니 연락처"), "PASS"),
]


def run_all():
    passed, failed = 0, []
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
