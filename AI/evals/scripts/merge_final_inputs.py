# AI/evals/scripts/merge_final_inputs.py
"""
정규화 완료된 기존 1,110건(matching_inputs_1110.json)에
동명이인 10건, 이름부분포함 10건을 추가로 합쳐서
트랙 B에게 전달할 최종 파일 하나(1,130건)를 만든다.

세 파일 모두 이미 정수 child_id를 쓰고 있고, journal_entry_id 범위가
1~1070 / 1101~1140 / 1151~1170으로 서로 겹치지 않으므로 그대로 이어붙이면 된다.
"""
import json
from pathlib import Path

GENERATED_DIR = Path(__file__).parent.parent / "generated"
MAIN_PATH = GENERATED_DIR / "matching_inputs_1110.json"          # 기존 1,110건 (1,070 + 안전망 40)
HOMONYM_PATH = GENERATED_DIR / "matching_동명이인_10건.json"       # 신규 10건
SUBSTRING_PATH = GENERATED_DIR / "matching_이름부분포함_10건.json"  # 신규 10건
OUT_PATH = GENERATED_DIR / "matching_inputs_final.json"           # 최종 1,130건


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    sources = {
        "기존 1,110건": load(MAIN_PATH),
        "동명이인 10건": load(HOMONYM_PATH),
        "이름부분포함 10건": load(SUBSTRING_PATH),
    }

    # journal_entry_id가 세 파일 사이에 서로 겹치는지 전부 확인 (안 겹쳐야 정상)
    id_sets = {name: {c["journal_entry_id"] for c in cases} for name, cases in sources.items()}
    names = list(id_sets.keys())
    for i in range(len(names)):
        for j in range(i + 1, len(names)):
            overlap = id_sets[names[i]] & id_sets[names[j]]
            if overlap:
                raise ValueError(f"'{names[i]}'와 '{names[j]}' 사이에 journal_entry_id가 겹칩니다: {overlap}")

    combined = []
    for cases in sources.values():
        combined.extend(cases)

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(combined, f, ensure_ascii=False, indent=2)

    detail = " + ".join(f"{name} {len(cases)}건" for name, cases in sources.items())
    print(f"합치기 완료: {detail} = 총 {len(combined)}건")
    print(f"저장 위치: {OUT_PATH}")


if __name__ == "__main__":
    main()