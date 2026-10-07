# AI/insight/prompts.py
"""
인사이트 프롬프트.

WRITING_RULES 의 문장은 CRITERIA.md §5 표의 "원칙" 칸과 글자까지 같아야 한다.
기준을 바꿀 때 순서: CRITERIA 수정 → test_criteria_sync FAIL → 여기 맞춤 → PASS.

고정 규칙은 system 에, 아이별 claims 는 user 에 둔다. 앞부분이 매번 같아야
프롬프트 캐싱이 먹는다 (매칭에서 98% 캐시 확인).
"""

from . import config

#: CRITERIA §5 와 1:1. 키 = config 플래그 이름.
WRITING_RULES = {
    "DROP_TRAIT": "성격·특성으로 일반화하지 않는다. 상황과 반응으로만 쓴다",
    "DROP_DIAGNOSIS": "진단이나 의학적 해석을 하지 않는다",
    "DROP_PRESCRIPTION": "처방하지 않는다. 기록된 대처와 결과만 쓴다",
    "KEEP_HEDGES": "원문이 추측이면 추측으로 남긴다. 원인을 새로 짐작하지 않는다",
    "DROP_SENTIMENT": "작성자의 감정 평가는 빼고 사실만 남긴다",
    "DROP_OTHER_CHILD_NAMES": "다른 아이 이름을 쓰지 않는다",
    "KEEP_FAILED_SUPPORT": "통하지 않은 대처도 남긴다. 피해야 할 방법도 유용한 정보다",
    "ONE_SITUATION": "인사이트 하나에 상황 하나만 담는다",
    "WRITE_INSTITUTION_TYPE": "기관마다 다르면 어느 기관인지 기관 종류로 쓴다. 기관 이름은 쓰지 않는다",
    "CROSS_WITHOUT_SUPPORT": "대처가 없어도 기관마다 반응이 다르면 인사이트로 쓴다",
    "RELATE_RECORDS": "기록 하나를 다시 말하지 않는다. 여러 기록을 묶거나 비교한 결과를 쓴다",
    "TAG_RELEVANCE": "쓸모 있는 기관은 그 상황이 일어나는 기관 종류로 고른다. 근거를 낸 기관인지로 정하지 않는다",
}


def _enabled_rules() -> list[str]:
    """config 에서 켜진 원칙만 프롬프트에 넣는다."""
    return [WRITING_RULES[f] for f in config.WRITING_RULE_FLAGS if getattr(config, f)]


def system_prompt() -> str:
    rules = "\n".join(f"- {r}" for r in _enabled_rules())
    types = "·".join(config.INSTITUTION_TYPES)
    return f"""당신은 발달장애 아동을 돌보는 학교·센터·학원의 관찰 요약을 읽고 인사이트를 찾는다.

인사이트란 여러 기록을 겹쳐 봐야만 보이는 "상황 → 반응 → 대처와 결과" 묶음이다.
"다른 선생님이 내일 이 아이를 만날 때 바로 쓸 수 있는 정보인가?" 에 예라고 답할 수 있을 때만 쓴다.
기록 하나에서 그대로 나오는 문장은 인사이트가 아니다. 아래 중 하나 이상이어야 한다.
- 묶기: 겉모습이 다른 장면을 같은 상황으로 묶는다
- 효과 비교: 같은 상황에서 통한 대처와 통하지 않은 대처를 나란히 둔다
- 기관 간 차이: 같은 상황에서 기관마다 반응이 다르다. 대처가 없어도 된다
- 변화: 기간 안에서 반응이 달라졌다

근거 규칙
- 근거는 아래 기록의 claim_id 로만 가리킨다. 목록에 없는 번호를 만들지 않는다.
- 인사이트 하나에 근거 claim 이 {config.MIN_EVIDENCE_CLAIMS}개 이상 있어야 한다. 한 번 있었던 일은 패턴이 아니다.
- 대처(supports)는 기록에 있을 때만 쓴다. 대처마다 그 대처가 적힌 claim_id 와 결과를 쓴다.
  결과가 기록에 없는 대처는 쓰지 않는다. 대처가 없으면 빈 배열로 둔다.
- 기관 이름과 날짜는 쓰지 않는다. 기관 종류({types})는 본문에 쓴다. 출처와 볼 수 있는 기관은 코드가 정한다.
- 패턴이 없으면 insights 를 빈 배열로 둔다. 억지로 만들지 않는다.

쓸모 태그
- relevant_institution_types 에는 이 인사이트를 알면 다음 돌봄에 쓸 수 있는 기관 종류를 {types} 중에서 고른다.
- relevance_reason 에는 그 이유를 한 문장으로 쓴다. 판단하기 어려우면 빈 배열과 빈 문자열로 둔다.

쓰는 원칙
{rules}

JSON 으로만 답한다.
{{"insights": [{{"situation": "언제, 어떤 맥락에서", "behavior": "그 상황에서 관찰된 반응",
  "supports": [{{"action": "누가 어떻게 했는지", "result": "어떻게 됐는지", "claim_ids": ["12-0"]}}],
  "claim_ids": ["12-0", "15-1"], "content": "교사가 읽을 한두 문장",
  "relevant_institution_types": ["학교", "센터"], "relevance_reason": "왜 그 기관에 쓸모 있는지 한 문장"}}]}}"""


def user_prompt(state: dict) -> str:
    """
    아이별 기록. claim 의 번호·날짜·기관 종류·문장만 넣는다.

    ⚠️ 인용(evidence.quote)은 넣지 않는다. claim 문장은 요약이 익명화했지만,
    인용은 원문이라 다른 아이 이름이 그대로 남아 있다 (CRITERIA §2).
    test_reflect.py 가 이 규칙을 확인한다.
    """
    lines = [
        f"[{c['claim_id']}] ({c['entry_date']}, {c['institution_type']}) {c['text']}"
        for c in state["claims"]
    ]
    return (
        f"아이: {state['child_name']}\n"
        f"기간: {state['period_from']} ~ {state['period_to']}\n\n"
        "기록:\n" + "\n".join(lines)
    )


def build_messages(state: dict) -> list[dict]:
    return [
        {"role": "system", "content": system_prompt()},
        {"role": "user", "content": user_prompt(state)},
    ]