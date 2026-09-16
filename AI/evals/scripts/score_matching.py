# AI/evals/scripts/score_matching.py
import json
import sys
from pathlib import Path
from collections import Counter

MANIFEST_PATH = Path(__file__).parent.parent / "manifests" / "잇다_파이프라인_테스트매니페스트.json"


def load_matching_cases():
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        manifest = json.load(f)
    return manifest["matching_test_cases"]["cases"]


def score(cases, outputs_by_case_id):
    """
    cases: 매니페스트의 정답 케이스 리스트
    outputs_by_case_id: {case_id: agent_output_dict} — 트랙 B의 실제 출력 또는 mock
    """
    results = {
        "명확": {"total": 0, "correct": 0},
        "텍스트혼동_다수아동언급": {"total": 0, "mismatched_correctly": 0},
        "애매_등록자간_유사이름": {"total": 0, "correct": 0},
        "미등록_이름겹침": {"total": 0, "correctly_rejected": 0},
        "미등록_이름안겹침": {"total": 0, "correctly_rejected": 0},
    }

    for case in cases:
        case_id = case["case_id"]
        난이도 = case["난이도"]
        output = outputs_by_case_id.get(case_id)

        if output is None:
            continue  # 아직 이 케이스에 대한 출력이 없으면 스킵 (mock 단계에서 일부만 넣을 수 있음)

        if 난이도 == "명확":
            results["명확"]["total"] += 1
            if output.get("matched_child_id") == case["expected_child_id"]:
                results["명확"]["correct"] += 1

        elif 난이도 == "텍스트혼동_다수아동언급":
            results["텍스트혼동_다수아동언급"]["total"] += 1
            # 정답: 로그 주인 child_id로 매칭돼야 하고, 언급된 다른 아동으로 잘못 붙으면 실패
            correct_owner = output.get("matched_child_id") == case["expected_child_id"]
            not_confused = output.get("matched_child_id") != case.get("텍스트내_언급된_다른아동_child_id")
            if correct_owner and not_confused:
                results["텍스트혼동_다수아동언급"]["mismatched_correctly"] += 1

        elif 난이도 == "애매_등록자간_유사이름":
            results["애매_등록자간_유사이름"]["total"] += 1
            if output.get("matched_child_id") == case["expected_child_id"]:
                results["애매_등록자간_유사이름"]["correct"] += 1

        elif 난이도 in ("미등록_이름겹침", "미등록_이름안겹침"):
            results[난이도]["total"] += 1
            # 정답: status가 unmatched거나, matched_child_id가 null이어야 함
            if output.get("status") == "unmatched" or output.get("matched_child_id") is None:
                results[난이도]["correctly_rejected"] += 1

    return results


def print_report(results):
    print("=" * 50)
    print("Matching Agent 채점 결과")
    print("=" * 50)

    m = results["명확"]
    if m["total"]:
        print(f"① 자동확정 정밀도(명확)      {m['correct']}/{m['total']}  {m['correct']/m['total']*100:.1f}%")

    t = results["텍스트혼동_다수아동언급"]
    if t["total"]:
        print(f"② 텍스트혼동 정답률           {t['mismatched_correctly']}/{t['total']}  {t['mismatched_correctly']/t['total']*100:.1f}%")

    p = results["애매_등록자간_유사이름"]
    if p["total"]:
        print(f"③ 유사쌍 정확도               {p['correct']}/{p['total']}  {p['correct']/p['total']*100:.1f}%")

    for key in ["미등록_이름겹침", "미등록_이름안겹침"]:
        u = results[key]
        if u["total"]:
            print(f"④ {key} 오탐 거부율    {u['correctly_rejected']}/{u['total']}  {u['correctly_rejected']/u['total']*100:.1f}%")


if __name__ == "__main__":
    cases = load_matching_cases()
    print(f"전체 Matching 케이스: {len(cases)}건 로드 완료")
    print(Counter(c["난이도"] for c in cases))

    mock_outputs = {} # 실제 사용 시 트랙 B 출력으로 채워짐

    results = score(cases, mock_outputs)
    print_report(results)