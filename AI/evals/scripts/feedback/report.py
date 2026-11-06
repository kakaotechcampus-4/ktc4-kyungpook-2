# AI/evals/scripts/feedback/report.py
"""
교사가 AI 초안을 얼마나 · 어떻게 고쳤는지 한 장으로 본다. **모델을 부르지 않는다.**

    python evals/scripts/feedback/report.py <내보낸.json> [--rows] [--save 결과.json]

입력은 `summary_result` 를 내보낸 JSON 배열이다 (뽑는 법은 README).

    [{"summary_id": 12, "raw_response": "...", "claims": "..."}, ...]

`raw_response` · `claims` 는 DB 에 TEXT(JSON 문자열)로 들어 있어 문자열이어도 되고
이미 푼 객체여도 된다.

⚠️ **아동 기록이 들어 있는 파일이다.** 레포에 커밋하지 않는다 (`evals/generated/` 아래 둔다).
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from kinds import ADD, DELETE, EDIT, KEEP, compare, tally  # noqa: E402


def _json(value, key: str):
    """TEXT 컬럼이면 풀고, 이미 객체면 그대로."""
    if value is None or value == "":
        return None
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"{key} 가 JSON 이 아니다: {exc}") from exc
    return value


def _claims(value, key: str) -> list[dict]:
    data = _json(value, key)
    if data is None:
        return []
    if isinstance(data, dict):          # raw_response 는 {"claims": [...]} 꼴이다
        data = data.get("claims")
    return [c for c in (data or []) if isinstance(c, dict)]


def load(path: Path) -> list[dict]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(rows, dict):
        rows = rows.get("records") or rows.get("rows") or []

    out = []
    for row in rows:
        draft = _claims(row.get("raw_response"), "raw_response")
        final = _claims(row.get("claims"), "claims")
        if not final:
            continue                    # 아직 승인 안 된 요약은 건너뛴다
        for item in compare(draft, final):
            out.append({**item, "summary_id": row.get("summary_id")})
    return out


BAR = 28


def _bar(part: int, whole: int) -> str:
    filled = round(BAR * part / whole) if whole else 0
    return "█" * filled + "·" * (BAR - filled)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="summary_result 를 내보낸 JSON")
    ap.add_argument("--rows", action="store_true", help="고친 문장을 하나씩 보여준다")
    ap.add_argument("--save", help="결과를 JSON 으로 저장")
    args = ap.parse_args()

    rows = load(Path(args.src))
    if not rows:
        sys.exit("승인된 요약이 없다. 팀원이 Gate 1 을 한 바퀴 눌러야 숫자가 생긴다 (README)")

    t = tally(rows)
    n = t["문장"]
    summaries = len({r["summary_id"] for r in rows})

    print(f"\n요약 {summaries}건 · 문장 {n}개\n")

    print("■ 교사가 무엇을 했나")
    for action in (KEEP, EDIT, DELETE, ADD):
        cnt = t["액션"].get(action, 0)
        print(f"  {action}  {_bar(cnt, n)}  {cnt:>4}  {cnt / n * 100:5.1f}%")

    print(f"\n  AI 초안을 그대로 쓴 비율   {t['유지비율'] * 100:.1f}%")
    print(f"  손댄 비율                  {t['손댄비율'] * 100:.1f}%")
    if t["수정량평균"] is not None:
        print(f"  수정 한 건당 글자 차이     {t['수정량평균']}자")

    if t["종류"]:
        print("\n■ 고친 종류 — **무엇이** 바뀌었나 (왜 바꿨는지는 모른다)")
        worst = max(t["종류"].values())
        for kind, cnt in t["종류"].items():
            print(f"  {kind}  {_bar(cnt, worst)}  {cnt:>4}")

    if t["반복오류"]:
        print("\n■ 반복 오류 — 같은 종류로 두 번 이상. 다음에 고칠 곳")
        for kind, cnt in t["반복오류"].items():
            print(f"  {kind}  {cnt}건")
    else:
        print("\n■ 반복 오류 없음")

    if t["액션"].get(DELETE):
        print(f"\n  ⚠️ 삭제 {t['액션'][DELETE]}건에는 **근거 검사가 버린 문장이 섞여 있다.**")
        print("     raw_response 는 ground 전이라 이 행만으로는 못 가른다.")
        print("     실데이터에서 버린 문장은 575 중 2건이었다 (CRITERIA.md §2-b).")

    if args.rows:
        print("\n■ 고친 문장")
        for r in rows:
            if r["action"] == KEEP:
                continue
            tag = f"{r['action']}{'·' + r['kind'] if r['kind'] else ''}"
            print(f"\n  [{tag}] summary {r['summary_id']}")
            if r["before"]:
                print(f"    전  {r['before']}")
            if r["after"]:
                print(f"    후  {r['after']}")

    print("\n  ※ 글자만 보고 센 값이다. 사실을 바로잡은 것인지 표현을 다듬은 것인지는")
    print("     글자가 말해 주지 않는다. 그 둘을 가르려면 교사에게 물어야 한다.\n")

    if args.save:
        out = Path(args.save)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"tally": t, "rows": rows}, ensure_ascii=False, indent=2),
                       encoding="utf-8")
        print(f"  저장: {out}\n")


if __name__ == "__main__":
    main()
