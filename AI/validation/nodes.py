# AI/validation/nodes.py

def perceive(state: dict) -> dict:
    """인식: 판정 대상 텍스트와 이슈 유형별 탐지 패턴을 불러옴"""
    content = state["content"]
    state["candidate_issues"] = []  # 아직 귀속 검증 전, 후보만
    return state


def plan(state: dict) -> dict:
    """계획: 후보로 잡힌 이슈들을 귀속 검증이 필요한지 분류"""
    # 예: "확정적 표현"류는 귀속 검증이 특히 중요 (누구에 대한 확정적 서술인지가 핵심)
    return state


def act(state: dict) -> dict:
    """
    행동: Luna 호출.
    핵심 — 단순히 "이 문서에 위험 표현이 있나?"가 아니라
    "판정 대상 아동 본인에 대한 서술에 위험 표현이 있나?"로 프롬프트 구성.
    """
    # LLM에게 "본문 전체" + "지금 판정 중인 아동이 누구인지"를 같이 줘서,
    # 위험 표현이 그 아동 본인 얘기인지를 판단하게 함
    ...
    return state


def reflect(state: dict) -> dict:
    """반영: 최종 검사 — Matching 피드백 그대로 적용하는 지점"""
    winner_issue = state.get("winner_issue")

    if state.get("llm_error"):
        return {**state, "verdict": "REVIEW", "reason": "모델 호출 실패"}

    # ⚠️ 여기가 이번에 받은 피드백을 반영하는 핵심 지점입니다.
    # "문서 안에 위험 표현이 하나라도 있는지"가 아니라
    # "지금 판정하려는 이 문장/이 아동에 대해 확실한 귀속 근거가 있는지"로 검사
    if winner_issue and not _issue_confirms_this_subject(state, winner_issue):
        return {**state, "verdict": "REVIEW", "reason": "위험 표현은 감지됐으나 귀속 근거 불확실"}

    return state


def _issue_confirms_this_subject(state: dict, issue: dict) -> bool:
    """
    Matching의 has_exact 버그 교훈:
    '문서 안에 있는지'가 아니라 '이 문장의 주어가 판정 대상 본인인지'를 확인.
    """
    ...