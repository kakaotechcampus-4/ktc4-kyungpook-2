import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from kinds import (ADD, DELETE, EDIT, KEEP, LONGER, NUMBER, OTHER, SHORTER,  # noqa: E402
                   TONE, compare, edit_kind, numbers, tally)

RESULTS = []


def check(title, ok, detail=""):
    RESULTS.append((title, ok, detail))


def claim(text, entry=1, quote="원문 조각", source=None):
    c = {"text": text, "evidence": [{"journal_entry_id": entry, "quote": quote}]}
    if source:
        c["source"] = source
    return c


def teacher(text):
    return {"text": text, "evidence": [{"journal_entry_id": None, "quote": text}], "source": "TEACHER"}


def main():
    print("교사 피드백 분류 — 모델 없이\n")

    print("■ 한글 수사도 숫자로 센다")
    check("아라비아 숫자", numbers("40분 놀았다")[40] == 1)
    check("한글 수사 — 세 번", numbers("공을 세 번 주고받았다")[3] == 1)
    check("한글 수사 — 아홉 번", numbers("아홉 번까지 이어갔다")[9] == 1)
    check("단위 없는 글자는 숫자가 아니다 (세수·한글)",
          not numbers("세수를 하고 한글을 읽었다"), f"{dict(numbers('세수를 하고 한글을 읽었다'))}")

    print("■ 무엇이 바뀌었나")
    check("끝 낱말만 다르면 말투",
          edit_kind("블록 영역에서 놀았습니다.", "블록 영역에서 놀았음.") == TONE)
    check("숫자가 바뀌면 숫자 (말투처럼 보여도)",
          edit_kind("공을 세 번 주고받았다.", "공을 다섯 번 주고받았다.") == NUMBER)
    check("숫자가 사라져도 숫자",
          edit_kind("공을 세 번 주고받았다.", "공을 주고받았다.") == NUMBER)
    check("숫자가 같으면 숫자로 보지 않는다",
          edit_kind("40분 정도 놀았습니다.", "40분 정도 놀았음.") == TONE)
    check("내용이 빠지면 줄임",
          edit_kind("친구와 블록을 쌓다가 무너뜨리고 다시 쌓았다.", "친구와 블록을 쌓았다.") == SHORTER)
    check("내용이 늘면 늘림",
          edit_kind("블록을 쌓았다.", "친구와 함께 블록을 높이 쌓았다.") == LONGER)
    check("길이가 같고 다른 데가 바뀌면 기타",
          edit_kind("블록을 쌓았다.", "모래를 담았다.") == OTHER,
          edit_kind("블록을 쌓았다.", "모래를 담았다."))

    print("■ source 로 교사가 한 일을 되살린다")
    draft = [claim("첫 문장.", 1, "조각1"), claim("둘째 문장.", 2, "조각2"), claim("셋째 문장.", 3, "조각3")]

    final = [claim("첫 문장.", 1, "조각1", "AI"),
             claim("둘째 문장임.", 2, "조각2", "EDITED"),
             teacher("교사가 쓴 문장.")]
    got = compare(draft, final)
    by = {r["action"]: r for r in got}
    check("유지 · 수정 · 추가 · 삭제가 한 번에 나온다",
          sorted(r["action"] for r in got) == sorted([KEEP, EDIT, ADD, DELETE]),
          f"{[r['action'] for r in got]}")
    check("수정이 **근거로** 초안과 짝지어진다 (글로 짝지으면 놓친다)",
          by[EDIT]["before"] == "둘째 문장.", f"{by[EDIT]['before']}")
    check("빠진 셋째 문장이 삭제로 잡힌다", by[DELETE]["before"] == "셋째 문장.")
    check("교사가 쓴 문장은 짝지을 초안이 없다", by[ADD]["before"] is None)

    far = compare([claim("원래는 전혀 다른 긴 문장이었다.", 7, "조각7")],
                  [claim("완전히 바뀐 짧은 글.", 7, "조각7", "EDITED")])
    check("많이 고쳐도 삭제+추가로 둘로 세지 않는다",
          len(far) == 1 and far[0]["action"] == EDIT,
          f"{[r['action'] for r in far]}")

    plain = compare(draft, [claim("첫 문장.", 1, "조각1", "AI"),
                            claim("둘째 문장.", 2, "조각2", "AI"),
                            claim("셋째 문장.", 3, "조각3", "AI")])
    check("그대로 승인하면 전부 유지다 (데이터가 0 이라는 신호)",
          {r["action"] for r in plain} == {KEEP})

    legacy = compare([claim("원문.", 5, "조각5")], [claim("고친 글.", 5, "조각5")])
    check("source 가 없어도 글이 다르면 수정으로 본다", legacy[0]["action"] == EDIT)

    print("■ 한 장으로 접기")
    t = tally(got)
    check("문장 수가 맞는다", t["문장"] == 4, f"{t['문장']}")
    check("유지 비율이 나온다", t["유지비율"] == 0.25, f"{t['유지비율']}")
    check("손댄 비율 = 1 − 유지 비율", abs(t["손댄비율"] + t["유지비율"] - 1) < 1e-9)

    many = compare(
        [claim(f"문장 {i} 입니다.", i, f"조각{i}") for i in range(1, 4)],
        [claim(f"문장 {i} 임.", i, f"조각{i}", "EDITED") for i in range(1, 4)])
    t2 = tally(many)
    check("같은 종류 2건 이상이면 반복 오류", t2["반복오류"].get(TONE) == 3, f"{t2['반복오류']}")

    one = tally(compare([claim("문장.", 9, "조각9")], [claim("문장임.", 9, "조각9", "EDITED")]))
    check("1건짜리는 반복 오류가 아니다", one["반복오류"] == {}, f"{one['반복오류']}")

    print()
    failed = 0
    for title, ok, detail in RESULTS:
        print(f"  {'✓' if ok else '✗'} {title}")
        if not ok:
            print(f"      {detail}")
            failed += 1
    print(f"\n  {len(RESULTS) - failed}/{len(RESULTS)} 통과", "✅" if not failed else "❌")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
