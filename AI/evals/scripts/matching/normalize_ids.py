import argparse
import json
import re
from pathlib import Path

GENERATED_DIR = Path(__file__).parent.parent.parent / "generated" / "matching"
ID_MAP_PATH = GENERATED_DIR / "id_map.json"


def child_to_int(s: str | None) -> int | None:
    if not s:
        return None
    m = re.match(r"(dev|holdout)_child_(\d+)$", s)
    if not m:
        raise ValueError(f"예상치 못한 child_id 형식: {s}")
    kind, n = m.group(1), int(m.group(2))
    return n if kind == "dev" else 80 + n


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        cases = json.load(f)

    child_id_map = {}
    raw_record_map = {}
    out = []

    for i, c in enumerate(cases, start=1):
        new_roster = []
        for r in c["roster"]:
            child_int = child_to_int(r["child_id"])
            child_id_map[child_int] = r["child_id"]
            new_roster.append({**r, "child_id": child_int})
        c["roster"] = new_roster

        expected_str = c.get("expected_child_id")
        c["expected_child_id"] = child_to_int(expected_str)
        if c["expected_child_id"] is not None:
            child_id_map[c["expected_child_id"]] = expected_str

        confusion_str = c.get("confusion_child_id")
        c["confusion_child_id"] = child_to_int(confusion_str)

        raw_key = (c.get("hint_name"), c.get("hint_birthdate"))
        if raw_key not in raw_record_map:
            raw_record_map[raw_key] = len(raw_record_map) + 1
        c["raw_record_id"] = raw_record_map[raw_key]

        c["journal_entry_id"] = i

        out.append(c)

    dst.parent.mkdir(parents=True, exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    with open(ID_MAP_PATH, "w", encoding="utf-8") as f:
        json.dump(
            {"child": child_id_map, "raw_record": {v: k for k, v in raw_record_map.items()}},
            f, ensure_ascii=False, indent=2, default=str,
        )

    print(f"변환 완료: {len(out)}건")
    print(f"저장: {dst}")
    print(f"ID 매핑표: {ID_MAP_PATH}")


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", choices=["dev", "consumed-holdout", "all"], default="dev")
    ap.add_argument("--src", help="직접 지정하면 --dataset 을 무시한다")
    ap.add_argument("--dst")
    args = ap.parse_args()
    args.src = Path(args.src) if args.src else GENERATED_DIR / f"matching_inputs_{args.dataset}.json"
    args.dst = Path(args.dst) if args.dst else GENERATED_DIR / f"matching_inputs_{args.dataset}_int.json"
    return args


if __name__ == "__main__":
    parsed = parse_args()
    main(parsed.src, parsed.dst)