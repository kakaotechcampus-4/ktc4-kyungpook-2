import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from matching import names, nodes  # noqa: E402

ROSTER = [SimpleNamespace(child_id=1, name="최성은", birthdate=None),
          SimpleNamespace(child_id=2, name="조수정", birthdate=None),
          SimpleNamespace(child_id=3, name="임유진", birthdate=None),
          SimpleNamespace(child_id=4, name="김민수", birthdate=None)]

SENTENCE = "밖으로 나가려는 횟수도 줄고 체육활동 중 참여 시간과 수행정도가 가장 높은 날이다."


def _fuzzy_names(text):
    return [h.name for h in names.find_name_hits(text, ROSTER) if not h.exact]


def case_fragment_dropped():
    return "조수정" not in _fuzzy_names(SENTENCE)


def case_real_typo_kept():
    return "임유진" in _fuzzy_names("오늘 임유젼이 블록을 쌓았다.")


def case_fragment_back_when_off():
    original = names.FUZZY_REQUIRE_SAME_SURNAME
    try:
        names.FUZZY_REQUIRE_SAME_SURNAME = False
        return "조수정" in _fuzzy_names(SENTENCE)
    finally:
        names.FUZZY_REQUIRE_SAME_SURNAME = original


NO_NAME = "체육 시간, 줄넘기를 시도한다. 친구들이 연속으로 넘는 모습을 한참 바라보았다."


def _judge(code_co_mention, model_answer, content=NO_NAME, exact=(), candidates=None):
    state = {
        "content": content,
        "roster": ROSTER, "hint_name": "김민수",
        "candidates": candidates or [{"child_id": 4, "confidence": 0.66}],
        "mentioned_child_ids": list(exact), "exact_child_ids": list(exact), "ambiguous_group": [],
        "co_mention": code_co_mention,
    }
    saved = nodes.ask_json, nodes.build_messages
    nodes.build_messages = lambda s: []
    nodes.ask_json = lambda messages: SimpleNamespace(data=model_answer, usage={})
    try:
        return nodes.llm_judge(state)
    finally:
        nodes.ask_json, nodes.build_messages = saved


MODEL_SAYS_CO = {"child_id": None, "confidence": 0.5, "co_mention": True, "co_mention_child_ids": []}


def case_invented_co_mention_dropped():
    return _judge(False, MODEL_SAYS_CO)["co_mention"] is False


MODEL_INVENTS_ID = {"child_id": None, "confidence": 0.5, "co_mention": True, "co_mention_child_ids": [2]}


def case_invented_ids_not_leaked():
    return 2 not in _judge(False, MODEL_INVENTS_ID)["mentioned_child_ids"]


def case_invented_ids_back_when_off():
    original = nodes.CO_MENTION_NEEDS_TWO_NAMES
    try:
        nodes.CO_MENTION_NEEDS_TWO_NAMES = False
        return 2 in _judge(False, MODEL_INVENTS_ID)["mentioned_child_ids"]
    finally:
        nodes.CO_MENTION_NEEDS_TWO_NAMES = original


def case_confirmed_co_mention_kept():
    return _judge(True, MODEL_SAYS_CO)["co_mention"] is True


def case_invented_back_when_off():
    original = nodes.CO_MENTION_NEEDS_TWO_NAMES
    try:
        nodes.CO_MENTION_NEEDS_TWO_NAMES = False
        return _judge(False, MODEL_SAYS_CO)["co_mention"] is True
    finally:
        nodes.CO_MENTION_NEEDS_TWO_NAMES = original



TWO_NAMES = "최성은과 조수정이 함께 블록을 쌓았다. 두 아이가 번갈아 블록을 올렸다."
MODEL_ADDS_ABSENT = {"child_id": 1, "confidence": 0.7, "co_mention": True, "co_mention_child_ids": [1, 2, 4]}
TWO_CANDS = [{"child_id": 1, "confidence": 1.0}, {"child_id": 2, "confidence": 1.0}]


def _judge_two():
    return _judge(True, MODEL_ADDS_ABSENT, content=TWO_NAMES, exact=(1, 2), candidates=TWO_CANDS)


def case_absent_child_not_added():
    out = _judge_two()
    return 4 not in out["mentioned_child_ids"] and 4 not in {c["child_id"] for c in out["candidates"]}


def case_present_children_kept():
    out = _judge_two()
    return out["co_mention"] is True and {1, 2} <= {c["child_id"] for c in out["candidates"]} \
        and {1, 2} <= set(out["mentioned_child_ids"])


def case_absent_child_back_when_off():
    original = nodes.CO_MENTION_NEEDS_TWO_NAMES
    try:
        nodes.CO_MENTION_NEEDS_TWO_NAMES = False
        return 4 in _judge_two()["mentioned_child_ids"]
    finally:
        nodes.CO_MENTION_NEEDS_TWO_NAMES = original


CASES = [
    ("① '수행정도' 는 '조수정' 오타가 아니다", case_fragment_dropped),
    ("① 성이 같은 진짜 오타 '임유젼' 은 잡는다", case_real_typo_kept),
    ("① 플래그를 끄면 예전처럼 걸린다 (롤백)", case_fragment_back_when_off),
    ("② 이름 없는 기록의 co_mention 은 버린다", case_invented_co_mention_dropped),
    ("② 이름 2개인 기록의 co_mention 은 받는다", case_confirmed_co_mention_kept),
    ("② 플래그를 끄면 예전처럼 받는다 (롤백)", case_invented_back_when_off),
    ("② 이름 없는 기록에서 모델이 준 ID 는 노출 목록에 안 들어간다", case_invented_ids_not_leaked),
    ("② 플래그를 끄면 그 ID 도 예전처럼 들어간다 (롤백)", case_invented_ids_back_when_off),
    ("② 이름 2개인 기록에서 본문에 없는 아이는 후보·노출 목록에 안 들어간다", case_absent_child_not_added),
    ("② 본문에 나온 두 아이는 그대로 남는다", case_present_children_kept),
    ("② 플래그를 끄면 본문에 없는 아이도 예전처럼 들어간다 (롤백)", case_absent_child_back_when_off),
]

if __name__ == "__main__":
    failed = 0
    for name, fn in CASES:
        try:
            ok = bool(fn())
        except Exception as exc:  # noqa: BLE001
            ok, name = False, f"{name}  ({type(exc).__name__}: {exc})"
        print(("PASS " if ok else "FAIL ") + name)
        failed += not ok
    print(f"\n{len(CASES) - failed}/{len(CASES)} 통과")
    sys.exit(1 if failed else 0)
