# AI/evals/scripts/validation/run_dev.py
import json
import sys
from pathlib import Path

# validation 패키지를 import할 수 있게 AI 폴더를 경로에 추가
sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from validation.graph import build_graph


def load_dev_inputs(limit: int | None = None):
    path = Path(__file__).parent.parent.parent / "generated" / "validation" / "validation_inputs.json"
    with open(path, encoding="utf-8") as f:
        inputs = json.load(f)
    dev_cases = [c for c in inputs if c["dataset"] == "dev"]
    if limit:
        dev_cases = dev_cases[:limit]
    return dev_cases


def run(limit: int | None = None):
    graph = build_graph()
    cases = load_dev_inputs(limit)
    print(f"실행 대상: {len(cases)}건")

    outputs_by_case_id = {}
    errors = []

    for i, case in enumerate(cases, 1):
        state = {"content": case["content"]}
        try:
            result = graph.invoke(state)
            outputs_by_case_id[case["case_id"]] = {
                "verdict": result["verdict"],
                "issue_types": result["issue_types"],
                "evidence": result["evidence"],
            }
        except Exception as e:
            errors.append((case["case_id"], str(e)))

        if i % 50 == 0:
            print(f"  {i}/{len(cases)} 진행...")

    print(f"완료: {len(outputs_by_case_id)}건 성공, {len(errors)}건 에러")
    if errors:
        print("에러 목록 (앞 5개):")
        for case_id, err in errors[:5]:
            print(f"  {case_id}: {err}")

    out_dir = Path(__file__).parent.parent.parent / "generated" / "validation"
    with open(out_dir / "dev_outputs.json", "w", encoding="utf-8") as f:
        json.dump(outputs_by_case_id, f, ensure_ascii=False, indent=2)

    return outputs_by_case_id


if __name__ == "__main__":
    # 표본 테스트하려면: python run_dev.py 30
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else None
    run(limit)