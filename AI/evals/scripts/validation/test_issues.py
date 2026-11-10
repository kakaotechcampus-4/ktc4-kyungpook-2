# AI/evals/scripts/validation/test_issues.py
"""
issues(사유·구간 짝)가 issue_types·evidence 와 어긋나지 않는지 확인하는 단위 테스트 (#177).

BE 는 Gate 1 에서 요약 문장 근거와 겹치는 구간의 사유만 보여준다. 그러려면
사유마다 자기 구간이 붙어 있어야 하고, 사유 종류는 issue_types 와 같아야 한다.

test_evidence_spans.py 와 같이 모델을 부르지 않는 결정적 규칙 검증이라
홀드아웃 절차가 필요 없다. reflect 에 모델 응답(llm_issues)을 직접 넣어 확인한다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from validation.nodes import reflect

PHONE = "어머니 연락처는 010-1234-5678 이다. 민수가 ADHD 약을 먹고 지우를 밀었다."
PHONE_SPAN = {"start": 9, "end": 22}


def state(content=PHONE, pii=None, llm_issues=None, subject_known=True, llm_error=False):
    return {
        "content": content,
        "structural_pii_hits": pii or [],
        "llm_issues": llm_issues or [],
        "subject_known": subject_known,
        "llm_error": llm_error,
    }


def llm(issue_type, quote, attributed=True):
    return {"issue_type": issue_type, "evidence_quote": quote, "attributed_to_subject": attributed}


def issue(issue_type, start=None, end=None):
    return {"type": issue_type, "start": start, "end": end}


def find(quote):
    start = PHONE.index(quote)
    return start, start + len(quote)


CASES = [
    # (이름, 입력 state, 기대 issues)
    (
        "정규식과 모델이 같은 전화번호 — 하나만 남는다",
        state(pii=[PHONE_SPAN], llm_issues=[llm("개인정보표현", "어머니 연락처는 010-1234-5678 이다.")]),
        [issue("개인정보표현", 9, 22)],
    ),
    (
        "사유가 다르면 구간이 겹쳐도 둘 다 남는다",
        state(llm_issues=[llm("진단명", "ADHD"), llm("위험행동표현", "ADHD 약을 먹고 지우를 밀었다")]),
        [issue("진단명", *find("ADHD")), issue("위험행동표현", *find("ADHD 약을 먹고 지우를 밀었다"))],
    ),
    (
        "판정 대상을 모르면 대상불명확, 위치 없음",
        state(subject_known=False),
        [issue("대상불명확")],
    ),
    (
        "모델 호출 실패면 모델호출실패, 위치 없음",
        state(llm_error=True),
        [issue("모델호출실패")],
    ),
    (
        "정규식 BLOCK 이 있으면 모델 실패여도 개인정보만",
        state(pii=[PHONE_SPAN], llm_error=True),
        [issue("개인정보표현", 9, 22)],
    ),
    (
        "대상을 몰라도 정규식 사유는 구간과 함께",
        state(pii=[PHONE_SPAN], subject_known=False),
        [issue("개인정보표현", 9, 22)],
    ),
    (
        "귀속되지 않은 후보는 issues 에도 없다",
        state(llm_issues=[llm("위험행동표현", "지우를 밀었다", attributed=False)]),
        [],
    ),
    (
        "원문에 없는 인용은 issues 에도 없다",
        state(llm_issues=[llm("진단명", "자폐")]),
        [],
    ),
    ("아무것도 없으면 빈 목록", state(), []),
]


def run():
    passed = 0
    failed = []

    for name, given, expected in CASES:
        result = reflect(given)
        actual = result["issues"]
        problems = []
        if actual != expected:
            problems.append(f"issues 기대={expected} 실제={actual}")
        # 약속: issues 의 사유 종류는 issue_types 와 같다
        if {i["type"] for i in actual} != set(result["issue_types"]):
            problems.append(f"사유 불일치 issues={actual} issue_types={result['issue_types']}")
        # 약속: evidence 의 구간은 모두 issues 에도 있다. 반대는 성립하지 않는다 —
        # evidence 는 사유를 섞어 좁은 구간만 남겨서, 다른 사유 구간을 품은 넓은 구간이 빠진다.
        paired = {(i["start"], i["end"]) for i in actual}
        lost = [e for e in result["evidence"] if (e["start"], e["end"]) not in paired]
        if lost:
            problems.append(f"issues 에 없는 evidence 구간 {lost}")

        if problems:
            failed.append((name, problems))
            print(f"[FAIL] {name}: {'; '.join(problems)}")
        else:
            passed += 1
            print(f"[PASS] {name}")

    print()
    print(f"결과: {passed}/{len(CASES)} 통과")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    run()
