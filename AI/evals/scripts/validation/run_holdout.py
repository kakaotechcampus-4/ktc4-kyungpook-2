"""
Validation Agent 최종 블라인드 검증 — holdout 채점.

- holdout 케이스를 돌리되, 직접 재작성한 16건(sealed_holdout_16.json)은
  원래 템플릿 문장 대신 재작성한 content로 바꿔 끼워서 돌린다.
- 실행(run_one)은 run_dev.py 것을 그대로 가져다 쓴다
  → dev와 holdout이 완전히 같은 방식으로 돌아가야 비교가 공정하다.
- 채점은 score.py의 score()를 그대로 쓰고,
  "전체 holdout"과 "재작성 16건만" 두 번 집계한다.
  (순환 검증 방지 효과는 재작성 16건 쪽 숫자가 보여준다)

⚠️ sealed 파일 내용은 이 스크립트가 읽기만 한다. 채점 전에 직접 열어보지 말 것.

실행 (AI 폴더에서):
    python3 evals/scripts/validation/run_holdout.py
"""
from dotenv import load_dotenv
from pathlib import Path
load_dotenv(Path(__file__).parent.parent.parent.parent / ".env")

import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))  # AI/
sys.path.insert(0, str(Path(__file__).parent))                       # 같은 폴더의 run_dev, score

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

    # sealed 16건이 전부 holdout 안에 있는지 먼저 확인 (case_id 오타 방지)
    missing = set(sealed) - holdout_ids
    if missing:
        raise SystemExit(f"sealed 파일의 case_id가 holdout에 없음: {sorted(missing)}")

    replaced = 0
    for case in holdout:
        s = sealed.get(case["case_id"])
        if s is None:
            continue
        # 정답 라벨이 바뀌었으면 재작성 과정에서 실수한 것 → 멈춤
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
    """치명적/준치명적 오류만 원문과 함께 출력 — 채점이 끝난 뒤에만 보이게."""
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