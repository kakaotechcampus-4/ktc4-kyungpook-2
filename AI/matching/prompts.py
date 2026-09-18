"""
Luna 에 보낼 프롬프트.

배치 순서가 중요하다. 안 변하는 것을 앞에, 매번 변하는 것을 뒤에 둔다.

    [고정]  역할·규칙·출력 형식   ← 모든 호출에서 동일
    [기관]  명부                  ← 같은 기관이면 동일
    [매회]  본문                  ← 매번 다름

캐싱을 지원하는 모델이면 앞 두 덩이가 재사용된다. 지원하지 않아도
이 순서를 지켜서 손해 볼 것은 없다.
"""

SYSTEM_RULES = """\
너는 장애아동 관찰 기록을 읽고, 그 기록이 명부에 있는 어느 아이의 것인지 판단한다.

판단 규칙
- 명부에 있는 아이 중에서만 고른다. 명부에 없으면 child_id 를 null 로 둔다.
- 확신이 서지 않으면 추측하지 않는다. 가장 비슷한 아이를 억지로 고르지 않는다.
- 본문에 이름이 없어도 별명·호칭·맥락으로 특정할 수 있으면 그 근거를 인용한다.
- co_mention 은 "이 기록이 누구의 관찰 기록인가" 를 가르는 판단이다.
  다른 아이가 등장해 무언가를 하더라도, 그 행동이 주인공을 향하거나 주인공의
  활동의 일부이면 co_mention 은 false 다. 주인공은 한 명이다.
    "A 가 물을 쏟자 B 가 휴지를 가져다줌"        → false (B 의 행동이 A 를 향함)
    "A 가 계단을 오르는 동안 B 가 손을 잡아줌"   → false
    "A 가 그림을 그렸고 B 가 옆에서 색을 골라줌" → false
  두 아이가 서로 무관한 각자의 행동을 하거나, 다투는 등 어느 쪽도 주인공이라
  할 수 없을 때만 true 다.
    "A 와 B 가 장난감으로 다투다 A 가 먼저 밀침" → true
    "A 는 블록을 쌓았고 B 는 그림을 그림"        → true
- confidence 는 근거의 강도만 반영한다. 후보가 하나뿐이라는 이유로 올리지 않는다.

인용 규칙
- quotes 에는 판단 근거가 된 부분을 본문에서 그대로 복사해 넣는다.
- 조사를 바꾸거나 다듬지 않는다. 글자 하나라도 다르면 근거로 쓰이지 못한다.
- 판단에 직접 쓰인 최소 구간만 넣는다. 문장 전체나 문단을 넣지 않는다.

출력 형식
JSON 객체 하나만 출력한다. 설명 문장을 붙이지 않는다.

{
  "child_id": 정수 또는 null,
  "confidence": 0.0 에서 1.0 사이 숫자,
  "quotes": ["본문에서 그대로 복사한 문자열", ...],
  "co_mention": true 또는 false,
  "co_mention_child_ids": [정수, ...]
}
"""


def _roster_block(roster) -> str:
    lines = [f"- child_id={e.child_id} 이름={e.name} 생년월일={e.birthdate}" for e in roster]
    return "명부\n" + "\n".join(lines)


def build_messages(state) -> list[dict]:
    roster = state["roster"]
    content = state["content"]
    hint_name = state.get("hint_name")

    system = SYSTEM_RULES + "\n" + _roster_block(roster)

    user = f"<record>\n{content}\n</record>"
    if hint_name:
        user += (
            f"\n\n표지에 적힌 이름은 '{hint_name}' 이다."
            " 참고만 하고, 본문이 다른 아이를 가리키면 본문을 따른다."
        )

    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]
