"""
Validation Agent의 4단계 노드 — 인식/계획/행동/반영.

핵심 원칙 (Matching 피드백에서 그대로 가져옴):
"문서에 위험 신호가 하나라도 있는지"가 아니라
"그 신호가 지금 판정 대상 아동 본인에게 귀속되는지"로 최종 판정한다.
"""
import re

from .config import ISSUE_LEVEL, STRUCTURAL_PII_PATTERNS, ATTRIBUTION_EXEMPT
from .llm import ask_json, LlmError, spans_for_quotes
from .prompts import build_messages


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
    """행동: Luna 호출. 여기서만 LLM을 쓴다. 프롬프트 내용은 prompts.py 에 있다."""
    print(f"[validation] journal_entry_id={state.get('journal_entry_id')} 처리 중")

    messages = build_messages(state["content"], state.get("subject_name"))

    try:
        result = ask_json(messages)
        state["llm_issues"] = result.data.get("issues", [])
        state["llm_error"] = False
    except LlmError as exc:
        state["llm_issues"] = []
        state["llm_error"] = True
        state["llm_error_detail"] = str(exc)

    return state


def _narrowest_spans(spans: list[dict]) -> list[dict]:
    """겹치는 근거 구간을 정리한다.

    구조적 정규식과 모델 인용이 같은 곳을 가리키면 구간이 그대로 쌓인다.
    예: "010-1234-5678" 과 "어머니 연락처는 010-1234-5678 이다."
    화면에서 같은 자리가 두 번 칠해지므로 하나만 남긴다.

    남기는 쪽은 좁은 구간이다 — 계약이 "판정 근거가 된 최소 구간만" 이라
    넓은 인용이 정확한 히트를 덮어쓰지 않게 한다. 반환 순서는 start 기준으로
    고정한다. 모델 응답 순서에 따라 배열이 흔들리지 않게 하기 위함이다.
    """
    unique = {(s["start"], s["end"]) for s in spans}
    kept = [
        (start, end)
        for start, end in unique
        if not any(
            (other_start, other_end) != (start, end)
            and other_start >= start
            and other_end <= end
            for other_start, other_end in unique
        )
    ]
    return [{"start": start, "end": end} for start, end in sorted(kept)]


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
            return {**state, "verdict": "BLOCK", "issue_types": issue_types, "evidence": _narrowest_spans(evidence)}
        return {**state, "verdict": "REVIEW", "issue_types": issue_types or ["모델호출실패"], "evidence": _narrowest_spans(evidence)}

    if not state.get("subject_known"):
        if verdict == "BLOCK":
            return {**state, "verdict": "BLOCK", "issue_types": issue_types, "evidence": _narrowest_spans(evidence)}
        return {**state, "verdict": "REVIEW", "issue_types": list(set(issue_types + ["대상불명확"])), "evidence": _narrowest_spans(evidence)}

    for candidate in state.get("llm_issues", []):
        issue_type = candidate.get("issue_type")
        if issue_type not in ISSUE_LEVEL:
            continue  # 허용된 유형 목록 밖 값은 무시 (Matching의 "명부 밖 ID 무시"와 같은 원리)

        if issue_type not in ATTRIBUTION_EXEMPT and not candidate.get("attributed_to_subject"):
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

    return {**state, "verdict": verdict, "issue_types": list(set(issue_types)), "evidence": _narrowest_spans(evidence)}
