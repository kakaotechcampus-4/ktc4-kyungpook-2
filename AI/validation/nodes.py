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


def _narrowest_issues(issues: list[dict]) -> list[dict]:
    """사유·구간 짝을 정리한다 (#177).

    겹침 정리는 사유별로 따로 한다. 사유를 섞어 정리하면 넓은 구간을 가진
    사유가 통째로 사라져 issue_types 와 어긋난다. 위치 없는 사유
    (대상불명확·모델호출실패)는 한 번만 남긴다.
    """
    by_type: dict[str, list[dict]] = {}
    no_span: set[str] = set()
    for issue in issues:
        if issue.get("start") is None:
            no_span.add(issue["type"])
        else:
            by_type.setdefault(issue["type"], []).append(issue)

    result = [
        {"type": issue_type, **span}
        for issue_type, spans in by_type.items()
        for span in _narrowest_spans(spans)
    ]
    result += [{"type": t, "start": None, "end": None} for t in no_span if t not in by_type]
    return sorted(result, key=lambda i: (i["start"] is None, i["start"] or 0, i["end"] or 0, i["type"]))


#: 이름 뒤에 붙는 조사. "하늘이는" 처럼 받침 없는 이름에 붙는 "이" 도 포함한다.
_NAME_TAILS = ("", "이", "이는", "이가", "이의", "이를", "이도", "이랑", "이와", "이한테", "이에게",
               "은", "는", "가", "의", "을", "를", "도", "랑", "와", "과", "한테", "에게")


def _is_subject_name_only(quote: str, subject_name: str | None) -> bool:
    """
    모델이 개인정보라고 가리킨 곳이 판정 대상 아이의 이름(성+이름 또는 이름)과 조사뿐인가.

    그 아이 일지에 그 아이 이름이 나오는 것은 당연하고 개인정보표현(연락처·주소·생년월일)이 아니다.
    그런데 모델이 가끔 이름을 개인정보로 판정해 평범한 일지를 BLOCK 한다 (같은 문장도 5번 중 2~5번).
    이 판정만 버린다. 글의 이름은 그대로 둔다. 전화번호·주민번호는 정규식이 따로 잡는다.
    """
    if not subject_name:
        return False
    q = re.sub(r"\s+", "", quote or "")
    name = re.sub(r"\s+", "", subject_name)
    forms = [name] + ([name[1:]] if len(name) >= 3 else [])  # 성을 뗀 이름은 세 글자 이상일 때만
    return any(q == form + tail for form in forms for tail in _NAME_TAILS)


def reflect(state: dict) -> dict:
    """반영: 최종 검사.

    피드백 반영 지점: 판정 대상이 누군지 모르면 귀속 검증 자체가
    성립하지 않는다. 이 경우 모델 응답과 무관하게 코드가 강제로 REVIEW로
    보낸다 (프롬프트 지시는 2차 방어선일 뿐, 1차는 여기).

    issues 는 issue_types·evidence 를 짝지은 것이다 (#177). 사유를 찾은
    자리에서 같이 담아 두 값이 어긋나지 않게 한다.
    """
    content = state["content"]
    verdict = "PASS"
    issue_types = []
    evidence = []
    issues = []

    def done(verdict: str, issue_types: list[str], issues: list[dict]) -> dict:
        return {
            **state,
            "verdict": verdict,
            "issue_types": issue_types,
            "evidence": _narrowest_spans(evidence),
            "issues": _narrowest_issues(issues),
        }

    # 구조적 개인정보는 귀속 검증 없이 항상 반영
    if state["structural_pii_hits"]:
        issue_types.append("개인정보표현")
        evidence.extend(state["structural_pii_hits"])
        issues.extend({"type": "개인정보표현", **hit} for hit in state["structural_pii_hits"])
        verdict = "BLOCK"

    if state.get("llm_error"):
        if verdict == "BLOCK":
            # 구조적 패턴(정규식)으로 이미 확정된 BLOCK은 모델 상태와 무관하게 유지
            return done("BLOCK", issue_types, issues)
        return done("REVIEW", issue_types or ["모델호출실패"], issues or [{"type": "모델호출실패"}])

    if not state.get("subject_known"):
        if verdict == "BLOCK":
            return done("BLOCK", issue_types, issues)
        return done(
            "REVIEW",
            list(set(issue_types + ["대상불명확"])),
            issues + [{"type": "대상불명확"}],
        )

    for candidate in state.get("llm_issues", []):
        issue_type = candidate.get("issue_type")
        if issue_type not in ISSUE_LEVEL:
            continue  # 허용된 유형 목록 밖 값은 무시 (Matching의 "명부 밖 ID 무시"와 같은 원리)

        if issue_type not in ATTRIBUTION_EXEMPT and not candidate.get("attributed_to_subject"):
            continue  # ⚠️ 귀속 검증 핵심 지점

        quote = candidate.get("evidence_quote", "")
        if issue_type == "개인정보표현" and _is_subject_name_only(quote, state.get("subject_name")):
            continue  # 주인공 이름을 개인정보로 본 판정은 버린다. 글은 그대로 둔다
        spans = spans_for_quotes(content, [quote])
        if not spans:
            continue  # 원문에서 못 찾은 근거는 신뢰하지 않음

        issue_types.append(issue_type)
        evidence.extend(spans)
        issues.extend({"type": issue_type, **span} for span in spans)

        level = ISSUE_LEVEL[issue_type]
        if level == "BLOCK":
            verdict = "BLOCK"
        elif level == "REVIEW" and verdict != "BLOCK":
            verdict = "REVIEW"

    return done(verdict, list(set(issue_types)), issues)
