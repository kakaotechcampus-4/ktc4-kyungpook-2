"""
잇다_파이프라인_테스트매니페스트.json의 validation_test_cases를
Validation Agent 입력 형태로 변환한다.

dataset(dev/holdout) 구분을 그대로 실어 보낸다 — 채점 시 dev로만 개발하고
holdout(특히 sealed 60건 재작성분)은 최종 검증 전까지 열어보지 않기 위함.
"""
import json
from pathlib import Path

PIPELINE_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_파이프라인_테스트매니페스트.json"
SYNTHETIC_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_synthetic_100.json"

def build_child_name_map():
    """child_id -> 이름 조회용 맵. Matching 쪽 데이터셋을 그대로 재사용."""
    with open(SYNTHETIC_PATH, encoding="utf-8") as f:
        data = json.load(f)
    all_children = data["dev"] + data["holdout"]
    return {c["child_id"]: c["이름"] for c in all_children}


def build_inputs():
    with open(PIPELINE_PATH, encoding="utf-8") as f:
        pipeline = json.load(f)
    name_map = build_child_name_map()

    inputs = []
    for case in pipeline["validation_test_cases"]["cases"]:
        child_id = case.get("child_id")
        inputs.append({
            "case_id": case["case_id"],
            "journal_entry_id": case["log_id"],
            "content": case["원본텍스트"],
            "expected_verdict": case["expected_판정"],
            "expected_issue_types": case["expected_이슈유형"],
            "dataset": case["데이터셋구분"],
            "subject_child_id": child_id,
            "subject_name": name_map.get(child_id),  # 못 찾으면 None
        })
    return inputs


if __name__ == "__main__":
    inputs = build_inputs()
    out_dir = Path(__file__).parent.parent.parent / "generated" / "validation"
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / "validation_inputs.json", "w", encoding="utf-8") as f:
        json.dump(inputs, f, ensure_ascii=False, indent=2)

    missing = sum(1 for i in inputs if i["subject_name"] is None)
    print(f"변환 완료: {len(inputs)}건 (subject_name 없음: {missing}건)")