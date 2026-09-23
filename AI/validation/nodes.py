# AI/validation/nodes.py
import re
import json
from validation.config import ISSUE_LEVEL, STRUCTURAL_PII_PATTERNS
from llm.luna_client import call_luna  # 기존 Matching에서 쓰던 공용 클라이언트 재사용


def perceive(state: dict) -> dict:
    """인식: 정규식으로 확인 가능한 구조적 개인정보부터 먼저 스캔"""
    content = state["content"]
    hits = []
    for pattern in STRUCTURAL_PII_PATTERNS:
        for m in re.finditer(pattern, content):
            hits.append({"start": m.start(), "end": m.end()})
    state["structural_pii_hits"] = hits
    return state


def plan(state: dict) -> dict:
    """계획: 별도 분기 없음 — 구조적 히트 여부와 무관하게 항상 LLM에도 물어봄
    (구조적 패턴은 개인정보표현만 잡아내고, 나머지 6개 유형은 LLM 판단이 필요하므로)"""
    return state


def act(state: dict) -> dict:
    """행동: Luna 호출. 여기서만 LLM을 씀"""
    content = state["content"]

    prompt = f"""
다음 관찰 기록에서 아래 7개 유형 중 해당하는 것이 있는지 판단하세요.

BLOCK: 진단명, 개인정보표현
REVIEW: 확정적표현, 다수아동언급, 추측성표현, 감정적표현, 위험행동표현

각 유형이 있다고 판단되면, 그게 "지금 이 기록의 주인공 아동 본인"에 대한
서술인지, 아니면 다른 사람(부모/형제/다른 아이)에 대한 언급일 뿐인지도
반드시 구분하세요. 본인에 대한 서술이 아니면 attributed_to_subject를
false로 표시하세요.

기록: {content}

아래 JSON 형식으로만 답하세요:
{{
  "issues": [
    {{"issue_type": "진단명", "attributed_to_subject": true, "evidence_text": "해당 근거 원문 일부"}}
  ]
}}
"""
    try:
        raw = call_luna(prompt)
        parsed = json.loads(raw)
        state["llm_issues"] = parsed.get("issues", [])
        state["llm_error"] = False
    except Exception:
        state["llm_issues"] = []
        state["llm_error"] = True

    return state


def _find_evidence_offset(content: str, evidence_text: str) -> dict | None:
    """LLM이 준 근거 문자열을 원문에서 찾아 offset으로 변환.
    못 찾으면(=LLM이 원문에 없는 걸 지어냈으면) None 반환 -> 그 근거는 버림"""
    idx = content.find(evidence_text)
    if idx == -1:
        return None
    return {"start": idx, "end": idx + len(evidence_text)}


def reflect(state: dict) -> dict:
    """반영: 최종 검사 — 여기가 이번에 받은 피드백을 반영하는 핵심 지점

    Matching 피드백 그대로 적용: "문서 안에 위험 신호가 있는지"가 아니라
    "그 신호가 지금 판정 대상 본인에게 귀속되는지"로 최종 확정 여부를 가른다.
    """
    content = state["content"]
    verdict = "PASS"
    issue_types = []
    evidence = []

    # 1. 구조적 개인정보(정규식)는 귀속 검증 없이 항상 반영
    if state["structural_pii_hits"]:
        issue_types.append("개인정보표현")
        evidence.extend(state["structural_pii_hits"])
        verdict = "BLOCK"

    # 2. 모델 호출 실패 시 안전하게 REVIEW로
    if state.get("llm_error"):
        return {**state, "verdict": "REVIEW", "issue_types": issue_types or ["모델호출실패"], "evidence": evidence}

    # 3. LLM이 찾은 후보들 — 귀속 검증 통과한 것만 최종 반영 (⚠️ 핵심 지점)
    for candidate in state.get("llm_issues", []):
        issue_type = candidate.get("issue_type")
        if issue_type not in ISSUE_LEVEL:
            continue  # 명부(허용된 유형 목록) 밖 값은 무시 — Matching의 "명부 밖 ID 무시"와 같은 원리

        if not candidate.get("attributed_to_subject"):
            continue  # ⚠️ 여기가 이번 피드백 반영 지점: 귀속 안 되면 반영 안 함

        offset = _find_evidence_offset(content, candidate.get("evidence_text", ""))
        if offset is None:
            continue  # 근거를 원문에서 못 찾으면(=지어낸 근거) 신뢰 안 함

        issue_types.append(issue_type)
        evidence.append(offset)

        level = ISSUE_LEVEL[issue_type]
        if level == "BLOCK":
            verdict = "BLOCK"
        elif level == "REVIEW" and verdict != "BLOCK":
            verdict = "REVIEW"

    return {**state, "verdict": verdict, "issue_types": list(set(issue_types)), "evidence": evidence}