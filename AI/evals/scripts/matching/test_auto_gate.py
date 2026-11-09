import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from matching import nodes  # noqa: E402
from matching.graph import GRAPH  # noqa: E402
from matching.llm import LlmResult  # noqa: E402
from matching.state import RosterEntry  # noqa: E402

ROSTER = [
    RosterEntry(child_id=1, name="김지후", birthdate="2020-03-01"),
    RosterEntry(child_id=2, name="박서연", birthdate="2020-07-15"),
    RosterEntry(child_id=3, name="김지유", birthdate="2021-05-02"),
]


def run(content, *, picks, hint_name=None, quotes=None, co_mention=False):
    original = nodes.ask_json
    nodes.ask_json = lambda messages, **kw: LlmResult(
        data={
            "child_id": picks,
            "confidence": 0.97,
            "quotes": quotes or [],
            "co_mention": co_mention,
            "co_mention_child_ids": [],
        }
    )
    try:
        return GRAPH.invoke(
            {
                "journal_entry_id": 1,
                "content": content,
                "roster": ROSTER,
                "hint_name": hint_name,
                "hint_birthdate": None,
            }
        )
    finally:
        nodes.ask_json = original


EXPECT_CANDIDATES = {
    "본문에 이름이 있는 아이는 후보에서 사라지지 않는다": {1, 2},
}

CASES = [
    (
        "오타로 걸린 아이를 고르면 자동 확정하지 않는다",
        "김지후가 블록을 높이 쌓았다.",
        3,
        None,
        "review",
        1,
        "김지유의 근거는 글자로 없다. 판단을 버리고 본문이 가리키는 김지후를 "
        "추천하되, 모델과 본문이 어긋났으므로 사람이 본다",
    ),
    (
        "본문에 없는 아이를 고르면 그 판단을 버린다",
        "김지후가 블록을 높이 쌓았다.",
        2,
        None,
        "review",
        1,
        "근거 없는 선택은 잡음이라 버린다. 박서연으로는 가지 않는다",
    ),
    (
        "고른 아이 이름이 본문에 있으면 자동 확정",
        "박서연이 블록을 높이 쌓았다.",
        2,
        None,
        "auto",
        2,
        "근거가 고른 아이 본인의 것이다",
    ),
    (
        "본문에 이름이 없어도 표지가 그 아이를 가리키면 자동 확정",
        "오늘 블록을 높이 쌓았다. 끝까지 혼자 해냈다.",
        2,
        "박서연",
        "auto",
        2,
        "관찰일지의 정상적인 형태다 — 표지에 이름, 본문에 관찰 내용",
    ),
    (
        "표지를 뒤집으려면 본문에 그 아이 이름이 있어야 한다",
        "김지후가 블록을 높이 쌓았다.",
        3,
        "김지후",
        "review",
        1,
        "표지와 모델이 어긋났고 모델 쪽에는 글자 근거가 없다",
    ),
    (
        "본문에 이름이 있는 아이는 후보에서 사라지지 않는다",
        "김지후가 박서연이를 도와주었다.",
        3,
        None,
        "multi",
        None,
        "교사에게 보일 후보는 글자 근거가 있는 둘이다 (M0134 재현)",
    ),
]


def main():
    print("■ 자동 확정 게이트 (모델 응답 고정, LLM 호출 없음)")
    failed = 0
    for name, content, picks, hint, want_status, want_child, why in CASES:
        final = run(content, picks=picks, hint_name=hint)
        got = (final["status"], final.get("matched_child_id"))
        ok = got[0] == want_status and (want_child is None or got[1] == want_child)
        print(f"  {'✓' if ok else '✗'} {name}")
        print(f"      기대 {want_status}/{want_child} · 실제 {got[0]}/{got[1]} — {why}")
        if name in EXPECT_CANDIDATES:
            want_ids = EXPECT_CANDIDATES[name]
            got_ids = {c["child_id"] for c in final.get("candidates", [])}
            cand_ok = got_ids == want_ids
            ok = ok and cand_ok
            print(f"      {'✓' if cand_ok else '✗'} 후보 기대 {sorted(want_ids)} · 실제 {sorted(got_ids)}")
        if not ok:
            failed += 1
    if failed:
        print(f"\n  실패 {failed}건")
        sys.exit(1)
    print("\n  전부 통과 ✅")


if __name__ == "__main__":
    main()
