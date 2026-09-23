# AI/validation/nodes.py (act, reflect 부분만 교체)
from .llm import ask_json, LlmError, spans_for_quotes


def act(state: dict) -> dict:
    """행동: Luna 호출. 여기서만 LLM을 씀"""
    content = state["content"]

    messages = [{
        "role": "user",
        "content": f"""다음 관찰 기록에서 아래 7개 유형 중 해당하는 것이 있는지 판단하세요.

BLOCK: 진단명, 개인정보표현
REVIEW: 확정적표현, 다수아동언급, 추측성표현, 감정적표현, 위험행동표현

각 유형이 있다고 판단되면, 그게 "지금 이 기록의 주인공 아동 본인"에 대한
서술인지, 아니면 다른 사람(부모/형제/다른 아이)에 대한 언급일 뿐인지도
반드시 구분하세요. 본인에 대한 서술이 아니면 attributed_to_subject를
false로 표시하세요. evidence_quote에는 근거가 되는 원문 문장을 그대로(가공하지 말고) 인용하세요.

기록: {content}

JSON 형식으로만 답하세요:
{{"issues": [{{"issue_type": "진단명", "attributed_to_subject": true, "evidence_quote": "원문 인용"}}]}}"""
    }]

    try:
        result = ask_json(messages)
        state["llm_issues"] = result.data.get("issues", [])
        state["llm_error"] = False
    except LlmError:
        state["llm_issues"] = []
        state["llm_error"] = True

    return state


def reflect(state: dict) -> dict:
    """반영: 최종 검사"""
    content = state["content"]
    verdict = "PASS"
    issue_types = []
    evidence = []

    if state["structural_pii_hits"]:
        issue_types.append("개인정보표현")
        evidence.extend(state["structural_pii_hits"])
        verdict = "BLOCK"

    if state.get("llm_error"):
        return {**state, "verdict": "REVIEW", "issue_types": issue_types or ["모델호출실패"], "evidence": evidence}

    for candidate in state.get("llm_issues", []):
        issue_type = candidate.get("issue_type")
        if issue_type not in ISSUE_LEVEL:
            continue
        if not candidate.get("attributed_to_subject"):
            continue  # ⚠️ 이번 피드백 반영 지점

        quote = candidate.get("evidence_quote", "")
        spans = spans_for_quotes(content, [quote])
        if not spans:
            continue  # 원문에서 못 찾은 근거(=지어낸 근거)는 버림

        issue_types.append(issue_type)
        evidence.extend(spans)

        level = ISSUE_LEVEL[issue_type]
        if level == "BLOCK":
            verdict = "BLOCK"
        elif level == "REVIEW" and verdict != "BLOCK":
            verdict = "REVIEW"

    return {**state, "verdict": verdict, "issue_types": list(set(issue_types)), "evidence": evidence}