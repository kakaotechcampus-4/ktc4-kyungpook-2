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
    "같은 아이의 같은 날 기록이 여러 기관에서 들어옵니다. 이것을 한 편의 글로 묶으세요."
)

#: 읽는 사람이 누구인지가 말투를 정한다 (schema.md §9.2).
READER = (
    "읽는 사람은 부모가 아니라 **교사와 다음 돌봄을 맡는 다른 기관 담당자**입니다. "
    "돌봄 기관의 전문가 기록체로 쓰세요. 무엇이 있었고 어떤 대응이 통했는지가 중심입니다."
)

#: 기관을 가로지르는 문장이 이 에이전트의 존재 이유다 (CRITERIA.md §1).
CROSS_RULE = (
    "기관마다 따로 쓰지 말고, 같은 일이 기관에 따라 어떻게 달랐는지를 한 문장 안에 "
    "담으세요. 예: \"학교에서는 혼자 쌓았고 센터에서는 도움이 필요했다\". "
    "그런 문장에는 근거를 일지마다 하나씩, 여러 개 답니다."
)

#: 근거 규칙. 인용을 다듬으면 코드가 못 찾아 그 근거가 버려진다.
EVIDENCE_RULE = (
    "문장마다 근거를 답니다. 근거의 quote 는 해당 일지 원문에서 **글자 그대로** "
    "복사하세요. 조사 하나라도 바꾸거나 줄이면 근거로 인정되지 않고 그 문장은 버려집니다. "
    "원문에 없는 내용은 쓰지 마세요 — 특히 이름·숫자·시간·기관명은 근거 인용 안에 "
    "그대로 있어야 합니다."
)

OUTPUT_FORMAT = (
    "JSON 형식으로만 답하세요. claims 배열의 순서가 그대로 글의 순서가 됩니다:\n"
    '{"claims": [{"text": "요약 문장 하나.", '
    '"evidence": [{"journal_entry_id": 1041, "quote": "원문에서 그대로 복사한 구절"}]}]}'
)


def _rules() -> list[str]:
    """켜진 플래그에 해당하는 규칙만 내보낸다."""
    rules = [CROSS_RULE, EVIDENCE_RULE]
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
    blocks = []
    for entry in sources:
        where = entry.institution_name or "기관 미상"
        blocks.append(
            f"[journal_entry_id: {entry.journal_entry_id}] ({where})\n{entry.content}"
        )
    return "\n\n".join(blocks)


def build_messages(state: SummaryState) -> list[dict]:
    user = (
        f"아이: {state['child_name']}\n"
        f"날짜: {state['entry_date']}\n"
        f"일지 {len(state['sources'])}건\n\n"
        f"{_source_block(state['sources'])}"
    )
    return [
        {"role": "system", "content": system_message()},
        {"role": "user", "content": user},
    ]
