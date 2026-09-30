# AI/evals/scripts/matching/baseline.py
"""
에이전트 없이 규칙만으로 매칭하는 기준선. 채점 결과를 읽는 잣대다.

    python evals/scripts/matching/baseline.py <입력.json> <결과.json> [--mode hint|both]
    python evals/scripts/matching/score.py <결과.json>

에이전트 점수 하나만 보면 잘한 건지 알 수 없다. 2026-09-16 에 자체 채점 96% 가
나왔는데, 표지만 읽는 이 코드가 같은 데이터에서 100% 를 받았다. 에이전트가 잘한 게
아니라 데이터가 쉬웠던 것이었고, 그걸 알아채는 데 시간이 걸렸다.

그래서 홀드아웃을 돌릴 때는 **항상 이것과 나란히** 채점한다. 에이전트가 기준선을
못 넘으면 그 홀드아웃에서 에이전트는 실패한 것이다.

LLM 을 부르지 않으므로 즉시 끝나고 결과가 매번 같다.
"""

import argparse
import json
import unicodedata
from pathlib import Path

#: run_eval.py 와 같은 모양으로 내보낸다 — score.py 가 둘을 구분하지 않는다.
PASSTHROUGH = (
    "case_id", "content", "hint_name",
    "expected_child_id", "expected_status", "expected_hint_mismatch",
    "expected_mentioned_child_ids", "expected_candidates_child_ids",
    "confusion_child_id", "category", "difficulty",
)


def nfc(text: str) -> str:
    """macOS 에서 만든 한글은 자모가 분리(NFD)돼 있어 그냥 비교하면 안 맞는다."""
    return unicodedata.normalize("NFC", text or "")


def by_hint(case: dict) -> int | None:
    """표지 이름을 명부에서 그대로 찾는다. 규칙 전부가 이 세 줄이다."""
    hint = nfc(case.get("hint_name"))
    if not hint:
        return None
    hits = [e for e in case["roster"] if nfc(e["name"]) == hint]
    return hits[0]["child_id"] if len(hits) == 1 else None


def by_body(case: dict) -> int | None:
    """본문에 명부의 이름이 그대로 들어 있는지 본다. 조사·경계는 보지 않는다."""
    content = nfc(case["content"])
    hits = [e for e in case["roster"] if nfc(e["name"]) in content]
    return hits[0]["child_id"] if len(hits) == 1 else None


def by_body_first(case: dict) -> int | None:
    """
    본문을 표지보다 앞세운다. 표지 이름이 본문에 없고 명부의 다른 이름이 본문에
    있으면 그쪽을 고른다 — "표지 A · 본문 B" 를 규칙만으로 맞히는 가장 싼 방법이다.

    이 기준선을 못 넘으면, 표지를 뒤집는 데 LLM 을 쓸 이유가 없다는 뜻이 된다.
    """
    content = nfc(case["content"])
    hint = nfc(case.get("hint_name"))
    if hint and hint in content:
        return by_hint(case)
    body = by_body(case)
    return body if body is not None else by_hint(case)


PICKERS = {
    "hint": by_hint,          # 표지만
    "both": by_body,          # 표지가 없으면 본문에서도
    "body-first": by_body_first,  # 본문이 표지와 어긋나면 본문을 따른다
}


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
