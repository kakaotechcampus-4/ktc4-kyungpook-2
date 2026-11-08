# AI/evals/scripts/insight/test_tone.py
"""
교사 말투가 남은 인사이트를 세는 검사. LLM 을 부르지 않는다 (#433).

    python evals/scripts/insight/test_tone.py

인사이트는 글이 하나고 말투는 보호자 기준(`~했어요`)이다 (CRITERIA §8-b). 읽는 사람이
보호자 · 다른 기관 담당자 · 교사인데 아무도 `~했다` 를 필요로 하지 않는다.

**막지 않고 센다.** 말투는 글의 값어치를 바꾸지 않지만 숫자·기관·이름은 사실을 바꾼다 —
막는 기준이 다르다. 여기서 재는 것은 "보호자가 교사 말투 글을 받는 비율" 이다.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from types import SimpleNamespace  # noqa: E402

from insight.nodes import _TEACHER_TONE, _tone_violations  # noqa: E402

CASES = [
    ("해요체", "차량 타기를 힘들어했지만 3분 뒤에 스스로 탔어요.", False),
    ("예요체", "같은 상황에서도 기관마다 반응이 달라요.", False),
    ("~했다", "차량 탑승을 거부했다.", True),
    ("~냈다 (어간 열거로는 놓쳤던 것)", "잘 지냈다.", True),
    ("~왔다", "먼저 다가왔다.", True),
    ("~이다", "같은 상황이다.", True),
    ("~함", "차량 탑승을 거부함", True),
    ("~됨", "6~7분 내 안정됨", True),
    ("~습니다", "차량 탑승을 거부했습니다.", True),
    ("'~다' 가 낱말 속에 있으면 통과", "기다려 주자 탔어요.", False),
    ("'~것 같다' 는 걸린다", "힘들어하는 것 같다.", True),
    ("문장 중간의 '했다가' 는 통과", "탔다가 다시 내렸어요.", False),
    ("'기관마다' 의 '다 ' 는 통과", "기관마다 반응이 달라요.", False),
    ("'보다' 도 통과", "지난주보다 빨리 탔어요.", False),
    ("문장부호 없이 끝나도 걸린다", "차량 탑승을 거부했다", True),
]


def case_patterns() -> bool:
    ok = True
    for name, text, expected in CASES:
        got = bool(_TEACHER_TONE.search(text))
        if got != expected:
            print(f"  FAIL {name}: {text!r} → {got} (기대 {expected})")
            ok = False
    return ok


def case_counts_not_drops() -> bool:
    """버리지 않는다 — 센 수만 돌려준다."""
    insights = [SimpleNamespace(content="잘 지냈어요."),
                SimpleNamespace(content="잘 지냈다."),
                SimpleNamespace(content="바뀐 모습이 보여요.")]
    return _tone_violations(insights) == 1 and len(insights) == 3


def case_empty() -> bool:
    return _tone_violations([]) == 0


ALL = [
    ("말투 패턴 10가지", case_patterns),
    ("세기만 하고 버리지 않는다", case_counts_not_drops),
    ("빈 목록은 0", case_empty),
]


if __name__ == "__main__":
    failed = 0
    for name, fn in ALL:
        try:
            ok = fn()
        except Exception as exc:  # 예상 못 한 에러도 실패로 센다
            ok = False
            name += f"  ({type(exc).__name__}: {exc})"
        print(("PASS " if ok else "FAIL ") + name)
        failed += not ok
    print(f"\n{len(ALL) - failed}/{len(ALL)} 통과")
    sys.exit(1 if failed else 0)
