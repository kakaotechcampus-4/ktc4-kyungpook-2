# AI/insight/prompts.py
"""
인사이트 프롬프트.

WRITING_RULES 의 문장은 CRITERIA.md §5 표의 "원칙" 칸과 글자까지 같아야 한다.
기준을 바꿀 때 순서: CRITERIA 수정 → test_criteria_sync FAIL → 여기 맞춤 → PASS.

고정 규칙은 system 에, 아이별 claims 는 user 에 둔다. 앞부분이 매번 같아야
프롬프트 캐싱이 먹는다 (매칭에서 98% 캐시 확인).
"""

import json

from . import config

#: CRITERIA §5 와 1:1. 키 = config 플래그 이름.
WRITING_RULES = {
    "DROP_TRAIT": "성격·특성으로 일반화하지 않는다. 상황과 반응으로만 쓴다",
    "DROP_DIAGNOSIS": "진단이나 의학적 해석을 하지 않는다",
    "DROP_PRESCRIPTION": "처방하지 않는다. 기록된 대처와 결과만 쓴다",
    "KEEP_HEDGES": "원문이 추측이면 추측으로 남긴다. 원인을 새로 짐작하지 않는다",
    "DROP_SENTIMENT": "작성자의 감정 평가는 빼고 사실만 남긴다. 감정이나 추측이 섞인 기록도 사실 부분은 근거로 쓴다",
    "DROP_OTHER_CHILD_NAMES": "다른 아이 이름을 쓰지 않는다",
    "KEEP_FAILED_SUPPORT": "통하지 않은 대처도 남긴다. 피해야 할 방법도 유용한 정보다",
    "ONE_SITUATION": "인사이트 하나에 상황 하나만 담는다. 같은 상황이면 기관이나 장면 이름이 달라도 한 인사이트에 함께 담는다",
    "SAME_SITUATION": "같은 상황인지는 장면 이름이나 겹치는 단어가 아니라 아이에게 요구되는 것이 같은지로 판단한다. 장면이 모두 달라도 요구되는 것이 같으면 같은 상황이다",
    "WRITE_INSTITUTION_TYPE": "기관마다 다르면 어느 기관인지 기관 종류로 쓴다. 기관 이름은 쓰지 않는다",
    "CROSS_WITHOUT_SUPPORT": "대처가 없어도 같은 상황에서 기관마다 반응이 다르면 인사이트로 쓴다. 상황이 다르면 기관이 달라도 묶지 않는다",
    "DROP_ADVICE_TAIL": "끝에 조언이나 활용 문장을 붙이지 않는다",
    "RELATE_RECORDS": "기록 하나를 다시 말하지 않는다. 여러 기록을 묶거나 비교한 결과를 쓴다",
    "NEED_DIFFERENCE": "같은 행동이 겹치기만 해서는 쓰지 않는다. 기관·대처·시점에 따라 반응이 달랐거나, 다음 사람이 알아야 할 어려움이 반복될 때만 쓴다",
    "PLAIN_TONE": '문장은 "~했다", "~이다"로 끝낸다. "~했습니다", "~함"은 쓰지 않는다',
    "DROP_HEALTH_ONLY": "질병·약·증상 같은 건강 상태만으로는 쓰지 않는다. 건강 기록은 다른 상황을 설명하는 맥락으로만 쓴다",
}


def _enabled_rules() -> list[str]:
    """config 에서 켜진 원칙만 프롬프트에 넣는다."""
    return [WRITING_RULES[f] for f in config.WRITING_RULE_FLAGS if getattr(config, f)]


def system_prompt() -> str:
    rules = "\n".join(f"- {r}" for r in _enabled_rules())
    types = "·".join(config.INSTITUTION_TYPES)
    return f"""당신은 발달장애 아동을 돌보는 {types}의 관찰 요약을 읽고 인사이트를 찾는다.

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
- 기관 이름과 날짜는 쓰지 않는다. 기관 종류({types})는 본문에 쓴다. 출처와 받을 기관은 코드가 정한다.
- 패턴이 없으면 insights 를 빈 배열로 둔다. 억지로 만들지 않는다.

쓰는 원칙
{rules}

JSON 으로만 답한다.
{{"insights": [{{"situation": "언제, 어떤 맥락에서", "behavior": "그 상황에서 관찰된 반응",
  "supports": [{{"action": "누가 어떻게 했는지", "result": "어떻게 됐는지", "claim_ids": ["12-0"]}}],
  "claim_ids": ["12-0", "15-1"], "content": "교사가 읽을 한두 문장"}}]}}"""


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


def build_refine_messages(state: dict, raw: dict, reasons: list[dict], allowed_ids: list[str]) -> list[dict]:
    """
    수정 루프 (nodes.refine). 처음 쓴 인사이트와 코드가 찾은 문제 문장을 주고 한 번 고치게 한다.

    기록은 처음 인사이트가 가리킨 claim 만 넣는다. 다른 claim 을 끌어와 새 인사이트를 만들지
    못하게 하기 위해서다 — 고치기는 빼는 일이지 새로 쓰는 일이 아니다. 인용은 여기서도 넣지 않는다.
    """
    index = {c["claim_id"]: c for c in state["claims"]}
    lines = [
        f"[{i}] ({index[i]['entry_date']}, {index[i]['institution_type']}) {index[i]['text']}"
        for i in allowed_ids if i in index
    ]
    keep = {k: raw.get(k) for k in ("situation", "behavior", "supports", "claim_ids", "content")}
    fixes = "\n".join(
        f'- {r["field"]}: "{r["sentence"]}"\n  → 기록이 없다는 말이 들어 있다. 다음 사람이 쓸 정보가 아니다. '
        "그 표현만 빼고 같은 문장의 나머지 내용은 남긴다."
        for r in reasons if r.get("sentence")
    )
    user = (
        f"아이: {state['child_name']}\n"
        f"기간: {state['period_from']} ~ {state['period_to']}\n\n"
        "기록 (이 인사이트의 근거):\n" + "\n".join(lines) + "\n\n"
        "처음 쓴 인사이트:\n" + json.dumps(keep, ensure_ascii=False) + "\n\n"
        "고칠 점:\n" + fixes + "\n\n"
        "고칠 점만 고친다. 위 기록에 없는 내용을 새로 쓰지 않고, 근거 claim_id 는 위 목록에서만 고른다. "
        "표현을 뺀 뒤 문장이 어색하면 자연스럽게 다듬는다. 그 표현을 빼서 결과가 없는 대처가 남으면, "
        "그 대처는 본문과 supports 에서 모두 뺀다. 결과가 기록에 없는 대처는 쓰지 않는다. 나머지 칸은 그대로 둔다.\n"
        "같은 JSON 형식으로 insights 에 인사이트 하나만 담아 답한다."
    )
    return [{"role": "system", "content": system_prompt()}, {"role": "user", "content": user}]


def build_lookup_messages(state: dict, raw: dict, claim_ids: list[str],
                          reasons: list[dict] | None = None) -> tuple[str, str]:
    """
    도구 사용 (nodes._lookup). 한 기관뿐인 인사이트를 지난 기록으로 보강하게 한다.

    /v1/responses 형식이라 (instructions, input) 두 덩어리로 돌려준다. 쓰는 원칙은 처음 쓸 때와
    같다. 기록은 이 인사이트의 근거 claim 만 넣는다. 인용은 넣지 않는다.
    """
    index = {c["claim_id"]: c for c in state["claims"]}
    lines = [
        f"[{i}] ({index[i]['entry_date']}, {index[i]['institution_type']}) {index[i]['text']}"
        for i in claim_ids if i in index
    ]
    keep = {k: raw.get(k) for k in ("situation", "behavior", "supports", "claim_ids", "content")}
    instructions = system_prompt() + (
        "\n\n지금 할 일: 아래 인사이트는 근거가 한 기관뿐이고 대처도 없어서, 다른 기관이 쓸 정보가 없다. "
        "find_claims 로 같은 아이의 지난 기록에서 같은 상황을 찾아본다. "
        "다른 기관에서 같은 상황이 있었으면, 그 기록의 claim_id 를 근거에 더해 기관 간 비교로 다시 쓴다. "
        "대처와 결과가 그 기록에 있으면 supports 에 쓴다. "
        "같은 상황이 없으면 억지로 묶지 말고 insights 를 빈 배열로 둔다. "
        "근거 claim_id 는 아래 기록과 find_claims 가 돌려준 것에서만 고른다. "
        # 다시 쓴 글도 quality.weak_reasons 를 다시 통과해야 한다. 이 말이 없으면 "…한 기록도 있었다" 처럼
        # 기록 자체를 말하는 표현을 새로 써서 기록에없는말 로 버려진다 (시연 데이터 3회 중 1회).
        "'기록되지 않았다', '기록도 있다', '확인되지 않았다', '알 수 없다' 처럼 기록에 대해 말하는 "
        "표현은 쓰지 않는다. 기록에 있는 사실만 쓴다."
    )
    user = (
        f"아이: {state['child_name']}\n"
        f"기간: {state['period_from']} ~ {state['period_to']}\n\n"
        "기록 (이 인사이트의 근거):\n" + "\n".join(lines) + "\n\n"
        "처음 쓴 인사이트:\n" + json.dumps(keep, ensure_ascii=False)
    )
    # 기록에없는말 이 같이 걸렸으면 그 문장도 빼게 한다 (build_refine_messages 와 같은 말)
    fixes = [r for r in (reasons or []) if r.get("sentence")]
    if fixes:
        user += "\n\n같이 고칠 점:\n" + "\n".join(
            f'- {r["field"]}: "{r["sentence"]}" → 기록이 없다는 말이다. 그 표현은 빼고 나머지는 남긴다.'
            for r in fixes)
    return instructions, user
