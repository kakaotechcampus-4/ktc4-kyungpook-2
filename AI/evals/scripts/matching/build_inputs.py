# AI/evals/scripts/matching/build_inputs.py
"""
매니페스트를 매칭 에이전트 입력으로 바꾼다.

    python evals/scripts/matching/build_inputs.py [--dataset dev|holdout|all]

**기본값은 dev 다.** 예전에는 dev 와 holdout 을 합쳐서 한 파일로 냈는데, 그 바람에
2026-09 튜닝에 쓴 1,130 건 안에 holdout 200 건이 통째로 섞여 들어갔다. 홀드아웃을
한 번 보고 그걸로 튜닝하면 그건 더 이상 홀드아웃이 아니다. 같은 일이 반복되지 않게
holdout 은 반드시 따로 요청해야 나오도록 바꿨다.

명부(roster)는 어느 쪽이든 등록 100명 전체다 — 명부는 문제가 아니라 보기이고,
정답 아이가 명부에 없으면 채점 자체가 성립하지 않는다.
"""
import argparse
import json
from pathlib import Path

MANIFEST_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_synthetic_100.json"
PIPELINE_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_파이프라인_테스트매니페스트.json"


def build_roster(data):
    """등록 100명 전체를 roster 형태로 변환. dataset 과 무관하게 항상 전체다."""
    all_children = data["dev"] + data["holdout"]
    return [{"child_id": c["child_id"], "name": c["이름"], "birthdate": c["생년월일"]} for c in all_children]


def build_inputs(dataset="dev"):
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        data = json.load(f)
    with open(PIPELINE_PATH, encoding="utf-8") as f:
        pipeline = json.load(f)

    roster = build_roster(data)
    all_logs = {log["id"]: (child, log) for child in (data["dev"] + data["holdout"]) for log in child["일지"]}
    children_by_id = {c["child_id"]: c for c in (data["dev"] + data["holdout"])}

    def wanted(child):
        """아이가 속한 세트가 이번 요청 대상인지."""
        return dataset == "all" or child.get("데이터셋구분") == dataset

    inputs = []
    skipped = 0
    for case in pipeline["matching_test_cases"]["cases"]:
        난이도 = case["난이도"]

        if 난이도 in ("명확", "텍스트혼동_다수아동언급"):
            child, log = all_logs[case["log_id"]]
            if not wanted(child):
                skipped += 1
                continue
            inputs.append({
                "case_id": case["case_id"],
                "journal_entry_id": log["id"],
                "raw_record_id": None,  # 정답 유출 방지 — normalize_ids.py가 나중에 새로 부여
                "content": log["원본텍스트"],
                "hint_name": child["이름"],
                "hint_birthdate": child["생년월일"],
                "roster": roster,
                # 진짜 정답은 여기서 명시적으로 실어 보냄 (raw_record_id에 숨기지 않음)
                "expected_child_id": child["child_id"],
                "confusion_child_id": case.get("텍스트내_언급된_다른아동_child_id"),
            })

        elif 난이도 == "애매_등록자간_유사이름":
            child = children_by_id.get(case["expected_child_id"])
            if child is not None and not wanted(child):
                skipped += 1
                continue
            inputs.append({
                "case_id": case["case_id"],
                "journal_entry_id": None,
                "raw_record_id": None,
                "content": case["input_설명"],
                "hint_name": case["expected_이름"],
                "hint_birthdate": case["expected_생년월일"],
                "roster": roster,
                "expected_child_id": case["expected_child_id"],
                "confusion_child_id": case.get("혼동주의_child_id"),
            })

        elif 난이도 in ("미등록_이름겹침", "미등록_이름안겹침"):
            # 미등록 아동은 dev/holdout 어느 세트도 아니다. 그런데 이 50건은 2026-09
            # 튜닝에 이미 썼으므로(UNMATCHED_WHEN_HINT_NOT_IN_ROSTER 가 여기서 나왔다)
            # holdout 요청에는 내보내지 않는다 — 소모된 케이스를 홀드아웃으로 세면 안 된다.
            if dataset == "holdout":
                skipped += 1
                continue
            inputs.append({
                "case_id": case["case_id"],
                "journal_entry_id": None,
                "raw_record_id": None,
                "content": case["input_설명"],
                "hint_name": case["input_이름"],
                "hint_birthdate": case["input_생년월일"],
                "roster": roster,
                "expected_child_id": None,  # 미등록이므로 정답은 "매칭 없음" = None
                "confusion_child_id": case.get("혼동주의_child_id"),
            })

    if skipped:
        print(f"  ({dataset} 이 아니라 건너뛴 케이스 {skipped}건)")
    return inputs


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--dataset",
        choices=["dev", "holdout", "all"],
        default="dev",
        help="어느 세트를 낼지. 기본 dev — holdout 은 명시해야만 나온다",
    )
    args = ap.parse_args()

    if args.dataset != "dev":
        print(f"⚠️  {args.dataset} 세트를 만듭니다. holdout 은 한 번 보면 소모됩니다.")

    inputs = build_inputs(args.dataset)
    print(f"변환 완료: {len(inputs)}건")

    output_dir = Path(__file__).parent.parent.parent / "generated" / "matching"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"matching_inputs_{args.dataset}.json"

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(inputs, f, ensure_ascii=False, indent=2)

    print(f"저장 위치: {output_path}")