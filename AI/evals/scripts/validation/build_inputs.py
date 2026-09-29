"""
잇다_파이프라인_테스트매니페스트.json의 validation_test_cases를
Validation Agent 입력 형태로 변환한다.

dataset(dev/holdout) 구분을 그대로 실어 보낸다 — 채점 시 dev로만 개발하고
holdout(특히 sealed 60건 재작성분)은 최종 검증 전까지 열어보지 않기 위함.

원본 매니페스트의 정답 라벨은 판정 기준 v1 이전에 만들어졌다. 바뀐 기준에 맞춘 정답은
fixtures/validation_label_overrides.json 의 규칙으로 덮어쓴다 (README "데이터의 한계" 6번).
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))  # AI/
from validation.config import ISSUE_LEVEL

PIPELINE_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_파이프라인_테스트매니페스트.json"
SYNTHETIC_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_synthetic_100.json"
# manifests/ 는 아이 데이터라 git 에서 무시되고, fixtures/ 는 우리가 손으로 만든 작은 파일이라 추적된다
OVERRIDES_PATH = Path(__file__).parent.parent.parent / "fixtures" / "validation_label_overrides.json"
LEVEL_RANK = {"PASS": 0, "REVIEW": 1, "BLOCK": 2}

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


def apply_overrides(inputs):
    """보정 규칙을 적용하고, 규칙별로 몇 건을 바꿨는지 돌려준다.

    add_issue_types 로 유형이 늘면 판정도 더 높은 쪽으로 다시 계산한다.
    optional_issue_types 는 잡아도 되고 안 잡아도 되는 유형이다 (run_suite 가 과탐으로 치지 않음).
    어떤 규칙이 적용됐는지 label_overrides 필드에 남겨 리포트에서 추적할 수 있게 한다.
    """
    if not OVERRIDES_PATH.exists():
        # 조용히 넘어가면 보정 없이 옛 라벨로 채점돼도 아무도 모른다. 없으면 멈춘다.
        raise SystemExit(f"라벨 보정 파일이 없음: {OVERRIDES_PATH}")
    with open(OVERRIDES_PATH, encoding="utf-8") as f:
        rules = json.load(f)["rules"]

    applied = {}
    for case in inputs:
        for rule in rules:
            # match_contains: 문장 그대로 매칭
            # match_template: 주인공 이름을 끼워서 매칭 (예: "{subject_name}가 먼저 밀쳤고")
            if "match_template" in rule:
                if not case.get("subject_name"):
                    continue
                needle = rule["match_template"].format(subject_name=case["subject_name"])
            else:
                needle = rule["match_contains"]
            if needle not in case["content"]:
                continue
            for t in rule.get("add_issue_types", []):
                if t not in case["expected_issue_types"]:
                    case["expected_issue_types"] = case["expected_issue_types"] + [t]
            opt = case.get("optional_issue_types", [])
            case["optional_issue_types"] = opt + [t for t in rule.get("optional_issue_types", []) if t not in opt]
            levels = [ISSUE_LEVEL[t] for t in case["expected_issue_types"]] or ["PASS"]
            case["expected_verdict"] = max(levels + [case["expected_verdict"]], key=LEVEL_RANK.get)
            case.setdefault("label_overrides", []).append(rule["id"])
            applied[rule["id"]] = applied.get(rule["id"], 0) + 1
    return applied


if __name__ == "__main__":
    inputs = build_inputs()
    applied = apply_overrides(inputs)
    out_dir = Path(__file__).parent.parent.parent / "generated" / "validation"
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / "validation_inputs.json", "w", encoding="utf-8") as f:
        json.dump(inputs, f, ensure_ascii=False, indent=2)

    missing = sum(1 for i in inputs if i["subject_name"] is None)
    print(f"변환 완료: {len(inputs)}건 (subject_name 없음: {missing}건)")
    for rule_id, n in applied.items():
        print(f"  라벨 보정 [{rule_id}]: {n}건")
