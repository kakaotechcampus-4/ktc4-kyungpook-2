# AI/evals/scripts/normalize_ids.py
import json
import re
from pathlib import Path

GENERATED_DIR = Path(__file__).parent.parent / "generated"
INPUT_PATH = GENERATED_DIR / "matching_inputs_생성됨.json"
OUTPUT_PATH = GENERATED_DIR / "matching_inputs_int.json"
ID_MAP_PATH = GENERATED_DIR / "id_map.json"


def child_to_int(s: str | None) -> int | None:
    """dev_child_001~080 -> 1~80, holdout_child_001~020 -> 81~100"""
    if not s:
        return None
    m = re.match(r"(dev|holdout)_child_(\d+)$", s)
    if not m:
        raise ValueError(f"예상치 못한 child_id 형식: {s}")
    kind, n = m.group(1), int(m.group(2))
    return n if kind == "dev" else 80 + n


def main():
    with open(INPUT_PATH, encoding="utf-8") as f:
        cases = json.load(f)

    child_id_map = {}      # 정수 -> 원래 문자열 (역추적용)
    raw_record_map = {}    # (hint_name, hint_birthdate) -> 정수 (같은 "파일"로 간주)
    out = []

    for i, c in enumerate(cases, start=1):
        # roster: 아이 ID를 문자열 -> 정수로 (100명 전원, 매 케이스마다 반복되지만 안전하게 매번 변환)
        new_roster = []
        for r in c["roster"]:
            child_int = child_to_int(r["child_id"])
            child_id_map[child_int] = r["child_id"]
            new_roster.append({**r, "child_id": child_int})
        c["roster"] = new_roster

        # 정답 라벨: build_matching_inputs.py가 이미 실어 보낸 expected_child_id를 정수로 변환
        # (raw_record_id에서 훔쳐오지 않음 — 애매/미등록 케이스도 정답이 살아있음)
        expected_str = c.get("expected_child_id")
        c["expected_child_id"] = child_to_int(expected_str)
        if c["expected_child_id"] is not None:
            child_id_map[c["expected_child_id"]] = expected_str

        confusion_str = c.get("confusion_child_id")
        c["confusion_child_id"] = child_to_int(confusion_str)

        # raw_record_id: 정답과 완전히 분리된 별도 번호 공간.
        # 같은 (hint_name, hint_birthdate) 조합 = 같은 파일에서 나온 것으로 간주해 번호를 묶음
        raw_key = (c.get("hint_name"), c.get("hint_birthdate"))
        if raw_key not in raw_record_map:
            raw_record_map[raw_key] = len(raw_record_map) + 1
        c["raw_record_id"] = raw_record_map[raw_key]

        # journal_entry_id: 중복/None 전부 해소, 1~N으로 재부여
        c["journal_entry_id"] = i

        out.append(c)

    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    with open(ID_MAP_PATH, "w", encoding="utf-8") as f:
        json.dump(
            {"child": child_id_map, "raw_record": {v: k for k, v in raw_record_map.items()}},
            f, ensure_ascii=False, indent=2, default=str,
        )

    print(f"변환 완료: {len(out)}건")
    print(f"저장: {OUTPUT_PATH}")
    print(f"ID 매핑표: {ID_MAP_PATH}")


if __name__ == "__main__":
    main()