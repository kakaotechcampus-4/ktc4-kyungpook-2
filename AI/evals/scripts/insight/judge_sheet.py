import argparse
import csv
import json
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
SHEET = AI_DIR / "evals" / "generated" / "insight" / "judge" / "loop_judge.csv"
CHOICES = {
    "고침": ("나아짐", "같음", "나빠짐"),
    "버림": ("버려도됨", "아까움"),
    "최종": ("쓸만함", "약함", "엉뚱한묶음"),
}
FIELDS = ["번호", "종류", "실행", "아이", "기간", "전", "후_또는_내용", "근거_요약", "고를것", "판정", "메모"]


def _evidence(run: dict, diary_ids: list[str]) -> str:
    by_entry = {e: s for s in run["summaries"] for e in s.get("entry_ids", []) if s.get("ok")}
    parts = []
    for d in diary_ids:
        s = by_entry.get(d)
        if s:
            parts.append(f"[{d} · {s['institution']}] {s['content']}")
    return "\n".join(parts)


def make(paths: list[str], sample: int, seed: int) -> None:
    rows, finals = [], []
    for p in sorted(paths):
        run = json.loads(Path(p).read_text(encoding="utf-8"))
        name = Path(p).stem.split("_all_")[-1]
        for r in run.get("refined", []):
            if r["outcome"] == "rescued":
                rows.append({"종류": "고침", "실행": name, "아이": r["child"], "기간": r["window"],
                             "전": r["before"], "후_또는_내용": r["after"]})
        for w in run.get("weak", []):
            if w.get("dropped"):
                kinds = sorted({x["kind"] for x in w["reasons"]})
                rows.append({"종류": "버림", "실행": name, "아이": w["child"], "기간": w["window"],
                             "전": "", "후_또는_내용": w["content"], "메모": f"이유: {', '.join(kinds)}"})
        rescued = {r["after"] for r in run.get("refined", []) if r["outcome"] == "rescued"}
        for ins in run["insights"]:
            if ins["content"] not in rescued:
                finals.append((name, run, ins))

    random.Random(seed).shuffle(finals)
    for name, run, ins in finals[:sample]:
        rows.append({"종류": "최종", "실행": name, "아이": ins["child"], "기간": ins["window"], "전": "",
                     "후_또는_내용": ins["content"], "근거_요약": _evidence(run, ins.get("source_diary_ids", []))})

    for n, r in enumerate(rows, 1):
        r["번호"] = n
        r["고를것"] = " / ".join(CHOICES[r["종류"]])
    SHEET.parent.mkdir(parents=True, exist_ok=True)
    with SHEET.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows({k: r.get(k, "") for k in FIELDS} for r in rows)
    count = Counter(r["종류"] for r in rows)
    print(f"판정표 {len(rows)}개 (고침 {count['고침']} · 버림 {count['버림']} · 최종 {count['최종']}) → {SHEET}")
    print("\"판정\" 칸에 \"고를것\" 중 하나를 그대로 적고 저장하세요. 이유가 있으면 \"메모\" 에.")


def ask() -> None:
    if not SHEET.exists():
        sys.exit(f"판정표가 없습니다. 먼저 make 를 돌리세요: {SHEET}")
    with SHEET.open(encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    todo = [r for r in rows if (r.get("판정") or "").strip() not in CHOICES[r["종류"]]]
    print(f"남은 판정 {len(todo)}개 / 전체 {len(rows)}개 · 그만두려면 q\n")
    guide = {"고침": "그 표현만 뺐나? 지어낸 말은 없나?",
             "버림": "한 기관 이야기여도 다음 선생님이 쓸 만했나?",
             "최종": "근거를 읽어 보고, 같은 상황을 묶었나? 다음 선생님이 쓸 수 있나?"}
    for r in todo:
        print("=" * 70)
        print(f"[{r['번호']}/{len(rows)}] {r['종류']} · {r['아이']} · {r['기간']}월 · {r['실행']}")
        if r["전"]:
            print(f"\n  전: {r['전']}\n")
            print(f"  후: {r['후_또는_내용']}")
        else:
            print(f"\n  {r['후_또는_내용']}")
        if r["근거_요약"]:
            print("\n  근거:")
            for line in r["근거_요약"].splitlines():
                print(f"    {line}")
        if r["메모"]:
            print(f"\n  ({r['메모']})")
        options = CHOICES[r["종류"]]
        print(f"\n  → {guide[r['종류']]}")
        while True:
            pick = input("  " + "  ".join(f"{i}) {o}" for i, o in enumerate(options, 1)) + "  : ").strip()
            if pick.lower() == "q":
                print("저장하고 그만둡니다.")
                return
            if pick.isdigit() and 1 <= int(pick) <= len(options):
                r["판정"] = options[int(pick) - 1]
                break
        note = input("  메모 (없으면 엔터): ").strip()
        if note:
            r["메모"] = (r["메모"] + " · " if r["메모"] else "") + note
        with SHEET.open("w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            w.writeheader()
            w.writerows(rows)
    print("\n다 했습니다. tally 로 집계하세요.")


def tally() -> int:
    if not SHEET.exists():
        sys.exit(f"판정표가 없습니다. 먼저 make 를 돌리세요: {SHEET}")
    with SHEET.open(encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    by_kind = defaultdict(Counter)
    bad = []
    for r in rows:
        v = (r.get("판정") or "").strip()
        if v not in CHOICES[r["종류"]]:
            bad.append(r["번호"])
            continue
        by_kind[r["종류"]][v] += 1
    print("===== 수정 루프 사람 판정 =====")
    for kind, options in CHOICES.items():
        c = by_kind[kind]
        total = sum(c.values())
        print(f"  {kind:<3} {total}개 · " + " · ".join(f"{o} {c[o]}" for o in options))
    if bad:
        print(f"  ⚠️ 판정이 비었거나 고를것과 다름: 번호 {bad}")
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("--runs", nargs="+", required=True, help="루프 켬(refine) 실행 결과 JSON")
    m.add_argument("--sample", type=int, default=10, help="최종 인사이트 표본 수")
    m.add_argument("--seed", type=int, default=7)
    sub.add_parser("ask")
    sub.add_parser("tally")
    args = ap.parse_args()
    if args.cmd == "make":
        make(args.runs, args.sample, args.seed)
    elif args.cmd == "ask":
        ask()
    else:
        sys.exit(tally())


if __name__ == "__main__":
    main()
