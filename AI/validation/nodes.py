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
    return state


def plan(state: dict) -> dict:
    """계획: 별도 분기 없음. 구조적 히트 여부와 무관하게 항상 LLM에도 물어본다
    (구조적 패턴은 개인정보표현만 잡고, 나머지 6개 유형은 LLM 판단이 필요하므로)."""
    return state


def act(state: dict) -> dict:
    """행동: Luna 호출. 여기서만 LLM을 쓴다.

    이슈 유형마다 정의를 명시한다. 처음엔 유형 이름만 던졌더니
    "감정적표현" 32건 중 8건, "다수아동언급"·"추측성표현" 일부를 놓쳤다.
    유형 이름만으로는 모델이 판단 기준을 스스로 추측해야 했기 때문으로 보고,
    정의를 프롬프트에 명시한 뒤 감정적표현 24/32 -> 31/32로 개선됨
    (2026-09-21 확인)."""
    content = state["content"]

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
    """반영: 최종 검사.

    Matching에서 받은 피드백 반영 지점 —
    "문서 안에 위험 신호가 있는지"가 아니라
    "그 신호가 지금 판정 대상 본인에게 귀속되는지"로 최종 확정한다.
    attributed_to_subject가 false면, 위험 표현이 감지됐어도 반영하지 않는다.

    또한 LLM이 인용한 근거가 원문에 실제로 없으면(=지어낸 근거) 버린다
    (spans_for_quotes가 못 찾으면 빈 리스트 반환).
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
        return {**state, "verdict": "REVIEW", "issue_types": issue_types or ["모델호출실패"], "evidence": evidence}

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