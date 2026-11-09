import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))
from validation.config import ISSUE_LEVEL

PIPELINE_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_파이프라인_테스트매니페스트.json"
SYNTHETIC_PATH = Path(__file__).parent.parent.parent / "manifests" / "잇다_synthetic_100.json"
OVERRIDES_PATH = Path(__file__).parent.parent.parent / "fixtures" / "validation_label_overrides.json"
LEVEL_RANK = {"PASS": 0, "REVIEW": 1, "BLOCK": 2}


def _require(path: Path) -> None:
    if not path.exists():
        raise SystemExit(f"{path} 가 없습니다. 가상 아동 데이터라 레포에 없습니다 — AI 담당(근찬)에게 받아 AI/evals/manifests/ 에 넣으세요.")

def build_child_name_map():
    _require(SYNTHETIC_PATH)
    with open(SYNTHETIC_PATH, encoding="utf-8") as f:
        data = json.load(f)
    all_children = data["dev"] + data["holdout"]
    return {c["child_id"]: c["이름"] for c in all_children}


def build_inputs():
    _require(PIPELINE_PATH)
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
            "subject_name": name_map.get(child_id),
        })
    return inputs


def apply_overrides(inputs):
    if not OVERRIDES_PATH.exists():
        raise SystemExit(f"라벨 보정 파일이 없음: {OVERRIDES_PATH}")
    with open(OVERRIDES_PATH, encoding="utf-8") as f:
        rules = json.load(f)["rules"]

    applied = {}
    for case in inputs:
        for rule in rules:
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
