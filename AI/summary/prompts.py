# AI/summary/prompts.py
"""
Summary Agent 프롬프트를 한 곳에 모은다.

규칙 문장은 CRITERIA.md 의 플래그와 1:1 이다. 기준을 바꿀 때는 CRITERIA.md 를
먼저 고치고, config 를 맞추고, 여기 문장을 옮긴다.
evals/scripts/summary/test_criteria_sync.py 가 셋이 같은지 검사한다.

**모델에게 요구하지 않는 것**이 중요하다.

    본문(content)        받지 않는다. 살아남은 claims 로 코드가 잇는다
    좌표(span)           받지 않는다. 인용문만 받고 locate_quote 가 찾는다
    covered_entry_ids    받지 않는다. 살아남은 근거에서 코드가 센다

모델이 내는 것은 claims 하나뿐이다. 나머지를 받으면 검사를 안 거친 값이
결과에 섞인다 — 그게 자기채점이다.
"""

from .config import DROP_SENTIMENT_KEEP_FACT, FIXED_LENGTH, KEEP_HEDGES
from .state import SummaryState

# ── 규칙 문장 (config 플래그와 1:1) ──────────────────────────────

#: KEEP_HEDGES
HEDGE_RULE = (
    "추측은 추측으로 남기세요. 원문이 \"~인 듯함\", \"~로 보임\" 이면 요약도 "
    "그렇게 쓰고, \"~이다\" 로 단정하지 마세요."
)

#: DROP_SENTIMENT_KEEP_FACT
SENTIMENT_RULE = (
    "작성자의 감정은 빼되 사실은 남기세요. \"너무 예뻤다\" 는 빼고, "
    "\"대응이 어려웠다\" 는 다음 돌봄에 필요한 정보이므로 남깁니다."
)

#: FIXED_LENGTH = False
LENGTH_RULE = (
    "길이를 맞추려 하지 마세요. 재료가 적은 날은 짧게 씁니다. "
    "재료보다 길게 늘여 쓰지 마세요."
)

ROLE = (
    "당신은 장애 아동을 돌보는 기관의 기록을 정리합니다. "
    "한 기관이 같은 아이에 대해 같은 날 쓴 기록 여러 건을 한 편의 글로 묶으세요."
)

#: 읽는 사람이 누구인지가 말투를 정한다 (schema.md §9.2).
READER = (
    "읽는 사람은 부모가 아니라 **교사와 다음 돌봄을 맡는 다른 기관 담당자**입니다. "
    "돌봄 기관의 전문가 기록체로 쓰세요. 무엇이 있었고 어떤 대응이 통했는지가 중심입니다."
)

#: 한 기관 안에서도 하루에 여러 기록이 올라온다. 그것들을 잇는 것이 이 일이다.
#: 기관을 가로지르는 비교는 여기서 하지 않는다 — child_context 를 읽는
#: 인사이트의 몫이다 (CRITERIA.md §1).
MERGE_RULE = (
    "기록을 한 건씩 나열하지 말고, 하루의 흐름이 보이도록 이으세요. "
    "같은 활동이 오전과 오후에 어떻게 달랐는지처럼 시간에 따른 변화가 있으면 "
    "한 문장 안에 담습니다. 그런 문장에는 근거를 일지마다 하나씩, 여러 개 답니다. "
    "다른 기관에서 있었던 일은 입력에 없으므로 추측해서 쓰지 마세요."
)

#: 근거 규칙. 인용을 다듬으면 코드가 못 찾아 그 근거가 버려진다.
EVIDENCE_RULE = (
    "문장마다 근거를 답니다. 근거의 quote 는 해당 일지 원문에서 **글자 그대로** "
    "복사하세요. 조사 하나라도 바꾸거나 줄이면 근거로 인정되지 않고 그 문장은 버려집니다. "
    "원문에 없는 내용은 쓰지 마세요 — 특히 이름·숫자·시간은 근거 인용 안에 "
    "그대로 있어야 합니다."
)

#: 얼마나 가져올지를 말하지 않으면 모델은 일지를 통째로 복사한다 — 줄일수록
#: 버려질 위험만 커지기 때문이다. 2026-10-06 측정에서 인용 6/6 이 원문 전체였다.
#: 대조는 통과하지만 Gate 1 에서 하이라이트가 일지 전체라 아무것도 알려주지 못한다.
#: 검증이 #59 에서 쓴 "판정 근거가 된 최소 구간만" 과 같은 계약이다.
QUOTE_SPAN_RULE = (
    "인용은 그 문장의 근거가 된 **최소 구간**만 가져오세요. 일지 전체나 여러 문장을 "
    "통째로 복사하지 마세요. 요약 문장이 말하지 않은 부분은 근거에 넣지 않습니다. "
    "예: 원문이 \"오전 블록 놀이에서 혼자 다섯 층까지 쌓음. 교사 개입 없이 끝까지 함.\" "
    "이고 요약 문장이 \"오전에는 혼자 쌓았다\" 라면, 인용은 \"혼자 다섯 층까지 쌓음\" "
    "입니다. 다만 짧게 줄이더라도 **원문에서 끊기지 않고 이어진 한 구간**이어야 합니다 — "
    "띄엄띄엄 이어 붙이면 원문에서 찾지 못해 그 근거가 버려집니다."
)

OUTPUT_FORMAT = (
    "JSON 형식으로만 답하세요. claims 배열의 순서가 그대로 글의 순서가 됩니다:\n"
    '{"claims": [{"text": "요약 문장 하나.", '
    '"evidence": [{"journal_entry_id": 1041, "quote": "원문에서 그대로 복사한 구절"}]}]}'
)


def _rules() -> list[str]:
    """켜진 플래그에 해당하는 규칙만 내보낸다."""
    rules = [MERGE_RULE, EVIDENCE_RULE, QUOTE_SPAN_RULE]
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
    """일지를 모델에게 보여준다. journal_entry_id 를 근거에 쓰게 해야 한다."""
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
