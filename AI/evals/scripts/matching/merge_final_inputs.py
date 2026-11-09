import json
from pathlib import Path

GENERATED_DIR = Path(__file__).parent.parent.parent / "generated" / "matching"
MAIN_PATH = GENERATED_DIR / "matching_inputs_1110.json"
HOMONYM_PATH = GENERATED_DIR / "matching_동명이인_10건.json"
SUBSTRING_PATH = GENERATED_DIR / "matching_이름부분포함_10건.json"
OUT_PATH = GENERATED_DIR / "matching_inputs_final.json"


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    sources = {
        "기존 1,110건": load(MAIN_PATH),
        "동명이인 10건": load(HOMONYM_PATH),
        "이름부분포함 10건": load(SUBSTRING_PATH),
    }

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