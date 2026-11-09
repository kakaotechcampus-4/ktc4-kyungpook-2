import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

AI_DIR = Path(__file__).resolve().parents[3]

HOWTO = [
    "이 파일이 Gate 1 화면을 대신합니다. 아래 `요약` 을 선생님이라 생각하고 고치세요.",
    "",
    "  그대로 둔다 → 아무것도 안 함",
    "  고친다      → text 를 고침 (id 는 건드리지 않음)",
    "  지운다      → 그 { } 덩어리를 통째로 삭제",
    "  새로 쓴다   → {\"text\": \"내가 쓴 문장.\"} 를 더함 (id 없이)",
    "",
    "`_초안` 은 비교 기준입니다. 손대지 마세요.",
    "",
    "다 봤는데 고칠 게 하나도 없었다면 `_다봤다` 를 true 로 바꿔 주세요.",
    "  (고친 것이 0 이면 apply 가 멈춥니다 — 안 고치고 돌린 것과 구별이 안 되기 때문입니다)",
]


def _draft_claims(record: dict) -> list[dict]:
    out = []
    for claim in record.get("claims") or []:
        evidence = [
            {"journal_entry_id": e.get("journal_entry_id"), "quote": e.get("quote")}
            for e in (claim.get("evidence") or [])
            if isinstance(e, dict)
        ]
        out.append({"text": (claim.get("text") or "").strip(), "evidence": evidence})
    return out


def cmd_draft(args) -> None:
    summaries, serial = [], 0
    seen: dict[str, int] = {}

    for path in args.from_run:
        src = json.loads(Path(path).read_text(encoding="utf-8"))
        records = src.get("records") if isinstance(src, dict) else src
        run = Path(path).stem

        for n, record in enumerate(records or [], 1):
            claims = _draft_claims(record)
            if not claims:
                continue
            case = str(record.get("case_id") or f"{run}-{n}")
            seen[case] = seen.get(case, 0) + 1
            label = case if seen[case] == 1 else f"{case}#{seen[case]}"

            rows = []
            for claim in claims:
                serial += 1
                rows.append({"id": serial, "text": claim["text"],
                             "근거": "; ".join(e["quote"] or "" for e in claim["evidence"])[:60]})
            summaries.append({
                "summary_id": label,
                "_출처": run,
                "_초안": claims,
                "요약": rows,
            })

    if not summaries:
        sys.exit("초안이 없다. --from-run 에 run_eval.py 결과 파일을 준다")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"_읽어주세요": HOWTO, "_다봤다": False, "요약들": summaries},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(len(s["요약"]) for s in summaries)
    again = sum(n - 1 for n in seen.values() if n > 1)
    print(f"\n요약 {len(summaries)}건 · 문장 {total}개 · 서로 다른 기록 {len(seen)}건 → {out}\n")
    for line in HOWTO:
        print(f"  {line}")
    print(f"\n  다 고쳤으면:  python {Path(__file__).name} apply {out} --out gate1.json\n")

    if total < 40:
        print(f"  ⚠️ 문장이 {total}개다. 반복 오류를 보려면 40~60개가 필요하다 (README).")
        print("     --from-run 에 결과 파일을 여러 개 준다.\n")
    if again:
        print(f"  ⚠️ 같은 기록을 다시 요약한 것이 {again}건 섞여 있다 (`#2` 가 붙은 것).")
        print("     글은 다르지만 바탕이 된 일지가 같다. 유지·수정 비율은 그대로 읽어도 되지만,")
        print("     **반복 오류는 부풀 수 있다** — 같은 일지에서 같은 실수가 거듭 나오기 때문이다.\n")


def cmd_apply(args) -> None:
    book = json.loads(Path(args.src).read_text(encoding="utf-8"))
    rows = []

    for summary in book.get("요약들") or []:
        draft = summary.get("_초안") or []
        if not draft:
            continue
        edited = summary.get("요약") or []

        final = []
        for item in edited:
            text = (item.get("text") or "").strip()
            if not text:
                continue
            serial = item.get("id")
            if serial is None:
                final.append({"text": text, "source": "TEACHER",
                              "evidence": [{"journal_entry_id": None, "quote": text}]})
                continue

            origin = _find(book, serial)
            if origin is None:
                sys.exit(f"id={serial} 에 맞는 초안이 없다. id 를 고치지 않았는지 보자")
            final.append({"text": text, "evidence": origin["evidence"],
                          "source": "AI" if text == origin["text"].strip() else "EDITED"})

        rows.append({
            "summary_id": summary.get("summary_id"),
            "raw_response": json.dumps({"claims": draft}, ensure_ascii=False),
            "claims": json.dumps(final, ensure_ascii=False),
        })

    touched = sum(1 for r in rows for a, b in [(json.loads(r["raw_response"])["claims"],
                                                json.loads(r["claims"]))]
                  if [c["text"].strip() for c in a] != [c["text"].strip() for c in b])
    if not touched and not book.get("_다봤다"):
        sys.exit(
            "\n고친 것이 하나도 없다.\n"
            f"  {Path(args.src)} 를 편집기로 열어 고치고 저장했는지 보자.\n"
            "  정말 61문장 모두 그대로 괜찮았다면 그 파일의 `_다봤다` 를 true 로 바꾸면 된다.\n"
            "  (안 고치고 돌린 것과 출력이 똑같아서, 그대로 받으면 숫자가 섞인다)\n"
        )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(rows)}건 · 손댄 요약 {touched}건 → {out}")
    print(f"  python {Path(__file__).parent.name}/report.py {out} --rows\n")


def _find(book: dict, serial: int) -> dict | None:
    count = 0
    for summary in book.get("요약들") or []:
        for claim in summary.get("_초안") or []:
            count += 1
            if count == serial:
                return claim
    return None


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("draft", help="평가 결과에서 고칠 거리를 뽑는다 (모델 안 부름)")
    d.add_argument("--from-run", required=True, nargs="+",
                   help="run_eval.py 가 만든 결과 JSON. 여러 개를 주면 합친다")
    d.add_argument("--out", default="evals/generated/feedback/mine.json")
    d.set_defaults(func=cmd_draft)

    a = sub.add_parser("apply", help="편집 결과를 summary_result 모양으로 바꾼다")
    a.add_argument("src", help="고친 mine.json")
    a.add_argument("--out", default="evals/generated/feedback/gate1.json")
    a.set_defaults(func=cmd_apply)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
