import argparse
import hashlib
import json
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path


def nfc(text):
    return unicodedata.normalize("NFC", text or "")


def check(cases):
    problems, warnings = [], []
    roster = cases[0]["roster"]
    names = {nfc(e["name"]) for e in roster}
    ids_by_name = defaultdict(list)
    for e in roster:
        ids_by_name[nfc(e["name"])].append(e["child_id"])
    valid_ids = {e["child_id"] for e in roster}

    seen = set()
    for c in cases:
        cid = c.get("case_id") or "(case_id 없음)"
        if cid in seen:
            problems.append(f"{cid}: case_id 중복")
        seen.add(cid)

        content = nfc(c["content"])
        hint = nfc(c.get("hint_name"))
        exp = c.get("expected_child_id")
        status = c.get("expected_status")
        in_body = sorted(n for n in names if n in content)

        if exp is not None and exp not in valid_ids:
            problems.append(f"{cid}: expected_child_id {exp} 가 명부에 없음")

        if status == "unmatched" and exp is not None:
            problems.append(f"{cid}: unmatched 인데 expected_child_id 가 있음")

        if c.get("expected_hint_mismatch") and not hint:
            problems.append(f"{cid}: hint_mismatch 인데 hint_name 이 비어 있음")

        if c.get("expected_hint_mismatch") and hint in content:
            problems.append(f"{cid}: 표지 이름 '{hint}' 가 본문에 그대로 있음")

        if status == "unmatched" and exp is None and in_body:
            problems.append(f"{cid}: 단서 없음이 정답인데 본문에 {in_body} 등장")

        if hint and hint not in names and status not in (None, "unmatched"):
            problems.append(f"{cid}: 표지 '{hint}' 가 명부에 없어 unmatched 가 강제됨 (기대={status})")

        if exp is not None:
            name = next((n for n, ids in ids_by_name.items() if exp in ids), None)
            if name and len(ids_by_name[name]) > 1 and status == "auto":
                problems.append(f"{cid}: '{name}' 이 명부에 {len(ids_by_name[name])}명인데 auto 기대")

        if c.get("expected_candidates_child_ids"):
            bad = [i for i in c["expected_candidates_child_ids"] if i not in valid_ids]
            if bad:
                problems.append(f"{cid}: 후보 {bad} 가 명부에 없음")

        if exp is None and status is None:
            warnings.append(f"{cid}: 정답 필드가 하나도 없어 채점에서 빠진다")

    return problems, warnings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="홀드아웃 세트 JSON")
    args = ap.parse_args()

    path = Path(args.src)
    raw = path.read_bytes()
    cases = json.loads(raw.decode("utf-8"))

    problems, warnings = check(cases)

    print(f"■ {len(cases)}건 검사 — {path.name}")
    print(f"  SHA-256 {hashlib.sha256(raw).hexdigest()}")
    if warnings:
        print(f"\n■ 경고 {len(warnings)}건")
        for w in warnings:
            print(f"  · {w}")
    if problems:
        print(f"\n■ 문제 {len(problems)}건 — 고치기 전에는 돌리지 않는다")
        for p in problems:
            print(f"  ✗ {p}")
        sys.exit(1)
    print("\n  자기모순 없음 ✅")


if __name__ == "__main__":
    main()
