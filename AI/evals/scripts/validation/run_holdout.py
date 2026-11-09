from dotenv import load_dotenv
from pathlib import Path
load_dotenv(Path(__file__).parent.parent.parent.parent / ".env")

import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))
sys.path.insert(0, str(Path(__file__).parent))

from validation.graph import build_graph
from run_dev import run_one, MAX_WORKERS
from score import score

GENERATED_DIR = Path(__file__).parent.parent.parent / "generated" / "validation"
INPUTS_PATH = GENERATED_DIR / "validation_inputs.json"
SEALED_PATH = GENERATED_DIR / "sealed" / "sealed_holdout_16.json"
OUTPUTS_PATH = GENERATED_DIR / "holdout_outputs.json"


def load_holdout_with_sealed():
    with open(INPUTS_PATH, encoding="utf-8") as f:
        inputs = json.load(f)
    with open(SEALED_PATH, encoding="utf-8") as f:
        sealed = {c["case_id"]: c for c in json.load(f)}

    holdout = [c for c in inputs if c["dataset"] == "holdout"]
    holdout_ids = {c["case_id"] for c in holdout}

    missing = set(sealed) - holdout_ids
    if missing:
        raise SystemExit(f"sealed 파일의 case_id가 holdout에 없음: {sorted(missing)}")

    replaced = 0
    for case in holdout:
        s = sealed.get(case["case_id"])
        if s is None:
            continue
        if s["expected_verdict"] != case["expected_verdict"] or \
           s["expected_issue_types"] != case["expected_issue_types"]:
            raise SystemExit(f"{case['case_id']}: sealed 정답 라벨이 원본과 다름")
        case["content"] = s["content"]
        case["sealed"] = True
        replaced += 1

    print(f"holdout {len(holdout)}건 로드, 그중 재작성 문장으로 교체: {replaced}건")
    return inputs, holdout


def run(holdout):
    graph = build_graph()
    outputs, errors = {}, []

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = [executor.submit(run_one, case, graph) for case in holdout]
        for i, future in enumerate(as_completed(futures), 1):
            case_id, output, error = future.result()
            if output is not None:
                outputs[case_id] = output
            else:
                errors.append((case_id, error))
            if i % 50 == 0 or i == len(holdout):
                print(f"  {i}/{len(holdout)} 완료...")

    print(f"완료: {len(outputs)}건 성공, {len(errors)}건 에러")
    for case_id, err in errors[:5]:
        print(f"  {case_id}: {err}")

    with open(OUTPUTS_PATH, "w", encoding="utf-8") as f:
        json.dump(outputs, f, ensure_ascii=False, indent=2)
    return outputs


def print_misses(holdout, outputs):
    print("\n놓친 케이스 (BLOCK→PASS, REVIEW→PASS):")
    found = False
    for case in holdout:
        out = outputs.get(case["case_id"])
        if out and case["expected_verdict"] in ("BLOCK", "REVIEW") and out["verdict"] == "PASS":
            found = True
            tag = " [재작성]" if case.get("sealed") else ""
            print(f"- {case['case_id']}{tag} 기대 {case['expected_verdict']} {case['expected_issue_types']}")
            print(f"  본문: {case['content']}")
            print(f"  출력: {out}")
    if not found:
        print("  없음")


if __name__ == "__main__":
    inputs, holdout = load_holdout_with_sealed()
    outputs = run(holdout)

    print("\n[1] 전체 holdout")
    score(holdout, outputs, dataset_filter="holdout")

    print("\n[2] 직접 재작성한 16건만 (순환 검증 방지 확인용)")
    score([c for c in holdout if c.get("sealed")], outputs, dataset_filter="holdout")

    print_misses(holdout, outputs)