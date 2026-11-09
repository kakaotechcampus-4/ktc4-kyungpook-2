import argparse
import json
import unicodedata
from pathlib import Path

PASSTHROUGH = (
    "case_id", "content", "hint_name",
    "expected_child_id", "expected_status", "expected_hint_mismatch",
    "expected_mentioned_child_ids", "expected_candidates_child_ids",
    "confusion_child_id", "category", "difficulty",
)


def nfc(text: str) -> str:
    return unicodedata.normalize("NFC", text or "")


def by_hint(case: dict) -> int | None:
    hint = nfc(case.get("hint_name"))
    if not hint:
        return None
    hits = [e for e in case["roster"] if nfc(e["name"]) == hint]
    return hits[0]["child_id"] if len(hits) == 1 else None


def by_body(case: dict) -> int | None:
    content = nfc(case["content"])
    hits = [e for e in case["roster"] if nfc(e["name"]) in content]
    return hits[0]["child_id"] if len(hits) == 1 else None


def by_body_first(case: dict) -> int | None:
    content = nfc(case["content"])
    hint = nfc(case.get("hint_name"))
    if hint and hint in content:
        return by_hint(case)
    body = by_body(case)
    return body if body is not None else by_hint(case)


def run_one(case: dict, mode: str) -> dict:
    record = {key: case.get(key) for key in PASSTHROUGH}
    record["expected_multi_reason"] = case.get("expected_multi_reason", "__skip__")

    child_id = by_hint(case)
    if child_id is None and mode == "both":
        child_id = by_body(case)
    elif mode == "body-first":
        child_id = by_body_first(case)

    record.update(
        status="auto" if child_id is not None else "unmatched",
        matched_child_id=child_id,
        confidence=1.0 if child_id is not None else 0.0,
        hint_mismatch=False,
        mentioned_child_ids=[],
        multi_reason=None,
        candidates=[],
        llm_called=False,
        error=None,
        diag={"baseline": mode},
    )
    return record


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="테스트 입력 JSON (run_eval.py 와 같은 파일)")
    ap.add_argument("dst", help="결과를 쓸 경로")
    ap.add_argument(
        "--mode",
        choices=["hint", "both", "body-first"],
        default="hint",
        help="hint = 표지만 (기본) / both = 표지 없으면 본문도 / "
             "body-first = 본문이 표지와 어긋나면 본문을 따른다 (가장 강한 기준선)",
    )
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    cases = json.loads(Path(args.src).read_text(encoding="utf-8"))
    if args.limit:
        cases = cases[: args.limit]

    results = [run_one(case, args.mode) for case in cases]
    Path(args.dst).write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"기준선({args.mode}) {len(results)}건 → {args.dst}")


if __name__ == "__main__":
    main()
