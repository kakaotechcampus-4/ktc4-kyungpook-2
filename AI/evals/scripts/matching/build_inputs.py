import argparse
import json
from pathlib import Path

MANIFEST_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_synthetic_100.json"
PIPELINE_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_파이프라인_테스트매니페스트.json"


def _require(path: Path) -> None:
    if not path.exists():
        raise SystemExit(f"{path} 가 없습니다. 가상 아동 데이터라 레포에 없습니다 — AI 담당(근찬)에게 받아 AI/evals/manifests/ 에 넣으세요.")


def build_roster(data):
    all_children = data["dev"] + data["holdout"]
    return [{"child_id": c["child_id"], "name": c["이름"], "birthdate": c["생년월일"]} for c in all_children]


def build_inputs(dataset="dev"):
    _require(MANIFEST_PATH)
    _require(PIPELINE_PATH)
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        data = json.load(f)
    with open(PIPELINE_PATH, encoding="utf-8") as f:
        pipeline = json.load(f)

    roster = build_roster(data)
    all_logs = {log["id"]: (child, log) for child in (data["dev"] + data["holdout"]) for log in child["일지"]}
    children_by_id = {c["child_id"]: c for c in (data["dev"] + data["holdout"])}

    manifest_key = "holdout" if dataset == "consumed-holdout" else dataset

    def wanted(child):
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
                "raw_record_id": None,
                "content": log["원본텍스트"],
                "hint_name": child["이름"],
                "hint_birthdate": child["생년월일"],
                "roster": roster,
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
                "expected_child_id": None,
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