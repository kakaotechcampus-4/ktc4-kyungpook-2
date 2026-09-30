# AI/evals/scripts/matching/build_inputs.py
"""
매니페스트를 매칭 에이전트 입력으로 바꾼다.

    python evals/scripts/matching/build_inputs.py [--dataset dev|holdout|all]

**기본값은 dev 다.** 예전에는 dev 와 holdout 을 합쳐서 한 파일로 냈는데, 그 바람에
2026-09 튜닝에 쓴 1,130 건 안에 holdout 200 건이 통째로 섞여 들어갔다.

그래서 매니페스트의 holdout 은 **이미 소모됐다.** 세트 이름을 `consumed-holdout`
으로 둔 건 그 때문이다 — 파일 이름이 `matching_inputs_consumed-holdout.json` 이라
나중에 누가 이걸 "블라인드 점수" 로 보고할 수 없다. 회귀 확인에는 쓸 수 있다.

**진짜 블라인드 점수가 필요하면 새 세트를 만들어야 한다.** HOLDOUT.md 참고.

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

    #: 옵션 이름과 매니페스트의 데이터셋 구분값을 잇는다.
    #: consumed-holdout 은 매니페스트에서 "holdout" 으로 표시된 아이들이다.
    manifest_key = "holdout" if dataset == "consumed-holdout" else dataset

    def wanted(child):
        """아이가 속한 세트가 이번 요청 대상인지."""
        return dataset == "all" or child.get("데이터셋구분") == manifest_key

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
            if dataset == "consumed-holdout":
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
        choices=["dev", "consumed-holdout", "all"],
        default="dev",
        help="어느 세트를 낼지. 기본 dev — consumed-holdout 은 명시해야만 나온다",
    )
    args = ap.parse_args()

    if args.dataset != "dev":
        print("⚠️  이 세트는 2026-09 튜닝에 이미 쓰였습니다 — 소모된 데이터입니다.")
        print("    회귀 확인용으로만 쓰고, 블라인드 점수로 보고하지 마세요.")

    inputs = build_inputs(args.dataset)
    print(f"변환 완료: {len(inputs)}건")

    output_dir = Path(__file__).parent.parent.parent / "generated" / "matching"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"matching_inputs_{args.dataset}.json"

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(inputs, f, ensure_ascii=False, indent=2)

    print(f"저장 위치: {output_path}")