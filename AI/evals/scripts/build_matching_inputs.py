# AI/evals/scripts/build_matching_inputs.py
import json
from pathlib import Path

MANIFEST_PATH = Path(__file__).parent.parent / "manifests" / "잇다_synthetic_100.json"
PIPELINE_PATH = Path(__file__).parent.parent / "manifests" / "잇다_파이프라인_테스트매니페스트.json"


def build_roster(data):
    """등록 100명 전체를 roster 형태로 변환"""
    all_children = data["dev"] + data["holdout"]
    return [{"child_id": c["child_id"], "name": c["이름"], "birthdate": c["생년월일"]} for c in all_children]


def build_inputs():
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        data = json.load(f)
    with open(PIPELINE_PATH, encoding="utf-8") as f:
        pipeline = json.load(f)

    roster = build_roster(data)
    all_logs = {log["id"]: (child, log) for child in (data["dev"] + data["holdout"]) for log in child["일지"]}

    inputs = []
    for case in pipeline["matching_test_cases"]["cases"]:
        난이도 = case["난이도"]

        if 난이도 in ("명확", "텍스트혼동_다수아동언급"):
            child, log = all_logs[case["log_id"]]
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

    return inputs


if __name__ == "__main__":
    inputs = build_inputs()
    print(f"변환 완료: {len(inputs)}건")

    output_dir = Path(__file__).parent.parent / "generated"
    output_dir.mkdir(exist_ok=True)

    with open(output_dir / "matching_inputs_생성됨.json", "w", encoding="utf-8") as f:
        json.dump(inputs, f, ensure_ascii=False, indent=2)

    print(f"저장 위치: {output_dir / 'matching_inputs_생성됨.json'}")