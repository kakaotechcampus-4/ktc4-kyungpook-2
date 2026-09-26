"""
Validation Agent를 dev 데이터로 돌려서 결과를 generated/validation/dev_outputs.json에 저장한다.

ThreadPoolExecutor로 동시 6개 처리 — Matching과 동일한 방식.
Luna 응답 대기가 대부분인 I/O 바운드 작업이라 스레드로 충분하다.
"""
from dotenv import load_dotenv
from pathlib import Path
load_dotenv(Path(__file__).parent.parent.parent.parent / ".env")

import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent))

from validation.graph import build_graph

MAX_WORKERS = 6


def load_dev_inputs(limit: int | None = None):
    path = Path(__file__).parent.parent.parent / "generated" / "validation" / "validation_inputs.json"
    with open(path, encoding="utf-8") as f:
        inputs = json.load(f)
    dev_cases = [c for c in inputs if c["dataset"] == "dev"]
    if limit:
        dev_cases = dev_cases[:limit]
    return dev_cases


def run_one(case: dict, graph) -> tuple[str, dict | None, str | None]:
    state = {
        "content": case["content"],
        "subject_name": case.get("subject_name"),
    }

    try:
        result = graph.invoke(state)
        output = {
            "verdict": result["verdict"],
            "issue_types": result["issue_types"],
            "evidence": result["evidence"],
        }
        return case["case_id"], output, None
    except Exception as e:
        return case["case_id"], None, str(e)


def run(limit: int | None = None):
    graph = build_graph()
    cases = load_dev_inputs(limit)
    print(f"실행 대상: {len(cases)}건 (동시 {MAX_WORKERS}개)")

    outputs_by_case_id = {}
    errors = []
    done = 0

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(run_one, case, graph): case for case in cases}

        for future in as_completed(futures):
            case_id, output, error = future.result()
            done += 1

            if output is not None:
                outputs_by_case_id[case_id] = output
            else:
                errors.append((case_id, error))

            if done % 50 == 0 or done == len(cases):
                print(f"  {done}/{len(cases)} 완료...")

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
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else None
    run(limit)