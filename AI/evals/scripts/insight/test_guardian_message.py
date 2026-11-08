# AI/evals/scripts/insight/test_guardian_message.py
"""
보호자 문장(guardian_message)이 제대로 걸러지는지 검사한다. LLM 을 부르지 않는다 (#433).

    python evals/scripts/insight/test_guardian_message.py

보호자 문장은 교사가 Gate 2 에서 **함께 승인한다.** 그래서 content 와 같은 근거에서 나온
같은 내용이어야 한다 — 승인한 적 없는 말이 섞이면 안 된다 (CRITERIA §11).

걸리면 그 문장만 버리고 인사이트는 남긴다. 교사용 글은 멀쩡한데 보호자용 표현 하나 때문에
패턴을 통째로 잃을 이유가 없다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from insight.nodes import _guardian_reason

CONTENT = "김지후는 차량 탑승을 거부했다. 교사가 먼저 타는 모습을 보여 주자 3분 뒤 탔다."
TYPES = {"센터"}
KNOWN = {"학교", "센터", "활동지원사"}

CASES = [
    ("정상 해요체", "차량 타기를 힘들어했지만 3분 뒤에 스스로 탔어요.", None),
    ("지어낸 숫자", "7분 뒤에 탔어요.", "숫자불일치"),
    ("근거에 없는 기관", "학교에서는 잘 지냈어요.", "기관불일치"),
    ("근거에 있는 기관", "센터에서는 차량 타기를 힘들어했어요.", None),
    ("교사 말투 ~했다", "차량 탑승을 거부했다.", "교사말투"),
    ("교사 말투 ~함", "차량 탑승을 거부함", "교사말투"),
    ("교사 말투 ~습니다", "차량 탑승을 거부했습니다.", "교사말투"),
    ("숫자 그대로 써도 통과", "3분 뒤에 탔어요.", None),
    ("'~다' 가 낱말 속에 있어도 통과", "기다려 주자 탔어요.", None),
    ("'같다' 는 걸린다", "힘들어하는 것 같다.", "교사말투"),
]

fail = 0
for name, message, expected in CASES:
    got = _guardian_reason(message, CONTENT, TYPES, KNOWN)
    ok = got == expected
    print(("PASS " if ok else f"FAIL ({got!r} != {expected!r}) ") + name)
    fail += not ok
print(f"\n{len(CASES)-fail}/{len(CASES)} 통과")
sys.exit(1 if fail else 0)
