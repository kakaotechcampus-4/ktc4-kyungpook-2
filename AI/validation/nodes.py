"""
Validation Agent의 4단계 노드 — 인식/계획/행동/반영.

핵심 원칙 (Matching 피드백에서 그대로 가져옴):
"문서에 위험 신호가 하나라도 있는지"가 아니라
"그 신호가 지금 판정 대상 아동 본인에게 귀속되는지"로 최종 판정한다.
"""
import re

from .config import ISSUE_LEVEL, STRUCTURAL_PII_PATTERNS
from .llm import ask_json, LlmError, spans_for_quotes


def perceive(state: dict) -> dict:
    """인식: 정규식으로 확인 가능한 구조적 개인정보부터 먼저 스캔.
    전화번호·주민번호 형식은 LLM 판단 없이도 항상 위험하다고 볼 수 있으므로
    코드로 먼저 잡아둔다."""
    content = state["content"]
    hits = []
    for pattern in STRUCTURAL_PII_PATTERNS:
        for m in re.finditer(pattern, content):
            hits.append({"start": m.start(), "end": m.end()})
    state["structural_pii_hits"] = hits
    state["subject_known"] = bool(state.get("subject_name"))
    return state


def plan(state: dict) -> dict:
    """계획: 별도 분기 없음. 구조적 히트 여부와 무관하게 항상 LLM에도 물어본다
    (구조적 패턴은 개인정보표현만 잡고, 나머지 6개 유형은 LLM 판단이 필요하므로)."""
    return state


def act(state: dict) -> dict:
    """행동: Luna 호출. 여기서만 LLM을 쓴다."""
    content = state["content"]
    subject_name = state.get("subject_name")

    # 대상을 알면 이름을 명시하고, 모르면 모델한테도 "모른다"고 알려서
    # attributed_to_subject를 스스로 false로 두게 유도한다 (2차 방어선).
    subject_line = (
        f'지금 판정 대상 아동은 "{subject_name}"입니다. 각 유형이 있다면, '
        f'그것이 {subject_name} 본인에 대한 서술인지 판단하세요.'
        if subject_name else
        '이번 요청에는 판정 대상 아동 정보가 제공되지 않았습니다. '
        '이 경우 attributed_to_subject는 항상 false로 표시하세요.'
    )

    messages = [{
        "role": "user",
        "content": f"""다음 관찰 기록에서 아래 7개 유형 중 해당하는 것이 있는지 판단하세요.

BLOCK:
- 진단명: 확정적인 진단명이 명시됨
- 개인정보표현: 연락처·생년월일·주소 등 개인 식별 정보

REVIEW:
- 확정적표현: 진단명은 아니지만 "절대 안 바뀐다"류의 단정적 서술
- 다수아동언급: 한 기록에 아이 2명 이상 등장
- 추측성표현: 근거 없이 원인을 추측하는 문장 ("아마~", "짐작건대~")
- 감정적표현: 객관적 관찰이 아니라 작성자(교사)의 주관적 감정이 드러나는 서술.
  지나친 애정 표현("너무 예뻐서", "사랑스러워서")이거나,
  힘들다는 하소연("지치고 힘든 하루였음")도 포함됩니다.
- 위험행동표현: 자해/타해 행동을 필요 이상으로 상세하게 묘사

{subject_line}

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
    """반영: 최종 검사.

    피드백 반영 지점: 판정 대상이 누군지 모르면 귀속 검증 자체가
    성립하지 않는다. 이 경우 모델 응답과 무관하게 코드가 강제로 REVIEW로
    보낸다 (프롬프트 지시는 2차 방어선일 뿐, 1차는 여기).
    """
    content = state["content"]
    verdict = "PASS"
    issue_types = []
    evidence = []

    # 구조적 개인정보는 귀속 검증 없이 항상 반영
    if state["structural_pii_hits"]:
        issue_types.append("개인정보표현")
        evidence.extend(state["structural_pii_hits"])
        verdict = "BLOCK"

    if state.get("llm_error"):
        if verdict == "BLOCK":
            # 구조적 패턴(정규식)으로 이미 확정된 BLOCK은 모델 상태와 무관하게 유지
            return {**state, "verdict": "BLOCK", "issue_types": issue_types, "evidence": evidence}
        return {**state, "verdict": "REVIEW", "issue_types": issue_types or ["모델호출실패"], "evidence": evidence}

    if not state.get("subject_known"):
        if verdict == "BLOCK":
            return {**state, "verdict": "BLOCK", "issue_types": issue_types, "evidence": evidence}
        return {**state, "verdict": "REVIEW", "issue_types": list(set(issue_types + ["대상불명확"])), "evidence": evidence}

    for candidate in state.get("llm_issues", []):
        issue_type = candidate.get("issue_type")
        if issue_type not in ISSUE_LEVEL:
            continue  # 허용된 유형 목록 밖 값은 무시 (Matching의 "명부 밖 ID 무시"와 같은 원리)

        if not candidate.get("attributed_to_subject"):
            continue  # ⚠️ 귀속 검증 핵심 지점

        quote = candidate.get("evidence_quote", "")
        spans = spans_for_quotes(content, [quote])
        if not spans:
            continue  # 원문에서 못 찾은 근거는 신뢰하지 않음

        issue_types.append(issue_type)
        evidence.extend(spans)

        level = ISSUE_LEVEL[issue_type]
        if level == "BLOCK":
            verdict = "BLOCK"
        elif level == "REVIEW" and verdict != "BLOCK":
            verdict = "REVIEW"

    return {**state, "verdict": verdict, "issue_types": list(set(issue_types)), "evidence": evidence}