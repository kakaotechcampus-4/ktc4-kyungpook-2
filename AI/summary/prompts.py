from .config import (
    ANONYMIZE_OTHER_NAMES,
    DROP_SENTIMENT_KEEP_FACT,
    FIXED_LENGTH,
    KEEP_HEDGES,
)
from .state import SummaryState

HEDGE_RULE = (
    "추측은 추측으로 남기세요. 원문이 \"~인 듯함\", \"~로 보임\" 이면 요약도 "
    "그렇게 쓰고, \"~이다\" 로 단정하지 마세요."
)

SENTIMENT_RULE = (
    "작성자의 감정은 빼되 사실은 남기세요. \"너무 예뻤다\" 는 빼고, "
    "\"대응이 어려웠다\" 는 다음 돌봄에 필요한 정보이므로 남깁니다."
)

LENGTH_RULE = (
    "길이를 맞추려 하지 마세요. 재료가 적은 날은 짧게 씁니다. "
    "재료보다 길게 늘여 쓰지 마세요."
)

ROLE = (
    "당신은 장애 아동을 돌보는 기관의 기록을 정리합니다. "
    "한 기관이 같은 아이에 대해 같은 날 쓴 기록 여러 건을 한 편의 글로 묶으세요."
)

READER = (
    "읽는 사람은 부모가 아니라 **교사와 다음 돌봄을 맡는 다른 기관 담당자**입니다. "
    "돌봄 기관의 전문가 기록체로 쓰세요. 무엇이 있었고 어떤 대응이 통했는지가 중심입니다."
)

MERGE_RULE = (
    "기록을 한 건씩 나열하지 말고, 하루의 흐름이 보이도록 이으세요. "
    "같은 활동이 오전과 오후에 어떻게 달랐는지처럼 시간에 따른 변화가 있으면 "
    "한 문장 안에 담습니다. 그런 문장에는 근거를 일지마다 하나씩, 여러 개 답니다. "
    "다른 기관에서 있었던 일은 입력에 없으므로 추측해서 쓰지 마세요."
)

EVIDENCE_RULE = (
    "문장마다 근거를 답니다. 근거의 quote 는 해당 일지 원문에서 **글자 그대로** "
    "복사하세요. 조사 하나라도 바꾸거나 줄이면 근거로 인정되지 않고 그 문장은 버려집니다. "
    "원문에 없는 내용은 쓰지 마세요 — 특히 이름·숫자·시간은 근거 인용 안에 "
    "그대로 있어야 합니다."
)

QUOTE_SPAN_RULE = (
    "인용은 그 문장의 근거가 된 **최소 구간**만 가져오세요. 일지 전체나 여러 문장을 "
    "통째로 복사하지 마세요. 요약 문장이 말하지 않은 부분은 근거에 넣지 않습니다. "
    "예: 원문이 \"오전 블록 놀이에서 혼자 다섯 층까지 쌓음. 교사 개입 없이 끝까지 함.\" "
    "이고 요약 문장이 \"오전에는 혼자 쌓았다\" 라면, 인용은 \"혼자 다섯 층까지 쌓음\" "
    "입니다. 다만 짧게 줄이더라도 **원문에서 끊기지 않고 이어진 한 구간**이어야 합니다 — "
    "띄엄띄엄 이어 붙이면 원문에서 찾지 못해 그 근거가 버려집니다."
)

ANONYMIZE_RULE = (
    "요약 대상 아이의 이름만 쓸 수 있습니다. 다른 아이가 나오면 **이름을 쓰지 말고** "
    "\"다른 아동\", \"옆자리 아동\", \"같은 모둠 아동\" 처럼 관계로만 적으세요. "
    "다만 **근거 인용(quote)은 원문 그대로** 둡니다 — 인용을 고치면 원문에서 찾지 못해 "
    "그 근거가 버려집니다. 바꾸는 것은 요약 문장(text)뿐입니다. "
    "교사·치료사 등 어른의 이름도 쓰지 말고 \"교사\" 로 적으세요."
)

NO_CONTENT_RULE = (
    "일지에 아이에 대해 기록할 관찰 사실이 없으면(예: \"특이사항 없음\", \"결석\" 한 줄뿐) "
    "문장을 지어내지 말고 {\"claims\": []} 로 답하세요."
)

TONE_RULE = (
    "문장은 \"~했다\", \"~이다\" 로 끝내세요. \"~했습니다\", \"~함\" 은 쓰지 마세요."
)

OUTPUT_FORMAT = (
    "JSON 형식으로만 답하세요. claims 배열의 순서가 그대로 글의 순서가 됩니다:\n"
    '{"claims": [{"text": "요약 문장 하나.", '
    '"evidence": [{"journal_entry_id": 1041, "quote": "원문에서 그대로 복사한 구절"}]}]}'
)


def _rules() -> list[str]:
    rules = [MERGE_RULE, EVIDENCE_RULE, QUOTE_SPAN_RULE, TONE_RULE, NO_CONTENT_RULE]
    if ANONYMIZE_OTHER_NAMES:
        rules.append(ANONYMIZE_RULE)
    if KEEP_HEDGES:
        rules.append(HEDGE_RULE)
    if DROP_SENTIMENT_KEEP_FACT:
        rules.append(SENTIMENT_RULE)
    if not FIXED_LENGTH:
        rules.append(LENGTH_RULE)
    return rules


def system_message() -> str:
    body = "\n".join(f"- {rule}" for rule in _rules())
    return f"{ROLE}\n\n{READER}\n\n규칙:\n{body}\n\n{OUTPUT_FORMAT}"


def _source_block(sources: list) -> str:
    return "\n\n".join(
        f"[journal_entry_id: {e.journal_entry_id}]\n{e.content}" for e in sources
    )


def build_messages(state: SummaryState) -> list[dict]:
    user = (
        f"아이: {state['child_name']}\n"
        f"날짜: {state['entry_date']}\n"
        f"기관: {state.get('institution_name') or '미상'}\n"
        f"일지 {len(state['sources'])}건\n\n"
        f"{_source_block(state['sources'])}"
    )
    return [
        {"role": "system", "content": system_message()},
        {"role": "user", "content": user},
    ]
