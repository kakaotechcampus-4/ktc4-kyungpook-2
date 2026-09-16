# AI/evals/scripts/merge_final_inputs.py
"""
정규화 완료된 기존 1,070건(matching_inputs_int.json)과
안전망 케이스 40건(matching_안전망케이스_40건.json)을 하나로 합쳐서
트랙 B에게 전달할 최종 파일 하나를 만든다.

두 파일 모두 이미 정수 child_id를 쓰고 있고, journal_entry_id 범위가
1~1070 / 1101~1140으로 겹치지 않으므로 그대로 이어붙이면 된다.
"""
import json
from pathlib import Path

GENERATED_DIR = Path(__file__).parent.parent / "generated"
MAIN_PATH = GENERATED_DIR / "matching_inputs_int.json"          # 1,070건 (정규화 완료)
SAFETY_NET_PATH = GENERATED_DIR / "matching_안전망케이스_40건.json"  # 40건 (이미 정수 ID)
OUT_PATH = GENERATED_DIR / "matching_inputs_final.json"           # 최종 1,110건


def main():
    with open(MAIN_PATH, encoding="utf-8") as f:
        main_cases = json.load(f)
    with open(SAFETY_NET_PATH, encoding="utf-8") as f:
        safety_cases = json.load(f)

    # journal_entry_id 겹치는지 확인 (안 겹쳐야 정상)
    main_ids = {c["journal_entry_id"] for c in main_cases}
    safety_ids = {c["journal_entry_id"] for c in safety_cases}
    overlap = main_ids & safety_ids
    if overlap:
        raise ValueError(f"journal_entry_id가 겹칩니다: {overlap}")

    combined = main_cases + safety_cases

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(combined, f, ensure_ascii=False, indent=2)

    print(f"합치기 완료: 기존 {len(main_cases)}건 + 안전망 {len(safety_cases)}건 = 총 {len(combined)}건")
    print(f"저장 위치: {OUT_PATH}")


if __name__ == "__main__":
    main()