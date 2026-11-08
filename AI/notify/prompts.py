# AI/notify/prompts.py
"""
메일 본문을 쓰는 프롬프트.

교사용 인사이트를 **보호자용으로 다시 쓰는 일**이다. 새 사실을 더하지 않는다 —
더한 것은 check 노드가 잡아서 메일 전체를 막는다.
"""

#: 보호자 말투. AI/summary/prompts.py 의 TONE_RULE 과 **반대로 간다.**
#: 요약은 기록이라 "~했다" 로 끝내고, 메일은 보호자에게 건네는 말이라 해요체다.
#: 같은 파일에 두 규칙이 있으면 섞여 나가므로 각자 둔다.
TONE_RULE = (
    "문장은 \"~했어요\", \"~예요\" 로 끝내세요. "
    "\"~했다\", \"~함\", \"~하였습니다\" 는 쓰지 마세요."
)

#: 기관을 나눠 쓰는 규칙. 인사이트가 여러 기관 기록에서 나왔을 때만 쓴다.
INSTITUTION_RULE = (
    "여러 기관 기록이 재료인 인사이트는 기관을 나눠 적으세요 — "
    "\"학교에서는 ~했어요. 센터에서는 ~했어요.\" "
    "재료에 없는 기관은 쓰지 마세요. 한 기관 기록뿐이면 기관을 나누지 마세요."
)

#: 더하지 말라는 규칙. 이것이 check 노드가 막는 것과 같은 내용이다.
GROUND_RULE = (
    "주어진 인사이트에 없는 내용을 쓰지 마세요. "
    "숫자, 날짜, 기관 이름, 다른 아이 이름을 새로 만들지 마세요. "
    "위로나 조언을 덧붙이지 마세요 — 기록에 없는 말입니다."
)

SYSTEM = (
    "당신은 아이의 한 주 기록을 보호자에게 전하는 글을 씁니다.\n"
    f"{TONE_RULE}\n{INSTITUTION_RULE}\n{GROUND_RULE}\n"
    "출력은 JSON 하나입니다.\n"
    '{"subject": "제목", "lines": [{"text": "문장.", "insight_index": 0}]}\n'
    "- subject 는 아이 이름과 기간이 드러나게 한 줄로 쓰세요.\n"
    "- lines 는 인사이트 하나당 한두 문장입니다.\n"
    "- insight_index 는 그 문장이 **어느 인사이트에서 나왔는지** 가리키는 번호입니다. "
    "반드시 넣으세요 — 없는 문장은 보내지 않습니다."
)


def user_message(child_name: str, period_from: str, period_to: str, insights: list) -> str:
    """
    모델에게 주는 재료.

    ⚠️ 다른 아이 이름(other_child_names)과 보호자 이메일은 여기 넣지 않는다.
    프롬프트에 없어야 모델이 쓸 수 없고, 쓰지 않은 것만 check 가 확인할 수 있다.
    """
    lines = [f"아이: {child_name}", f"기간: {period_from} ~ {period_to}", "", "인사이트:"]
    for i, ins in enumerate(insights):
        types = " · ".join(ins.institution_types) or "기관 미상"
        lines.append(f"[{i}] (근거 기관: {types}) {ins.content}")
    return "\n".join(lines)
