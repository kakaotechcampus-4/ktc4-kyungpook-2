import re

from .config import ISSUE_LEVEL, STRUCTURAL_PII_PATTERNS, ATTRIBUTION_EXEMPT
from .llm import ask_json, LlmError, spans_for_quotes
from .prompts import build_messages


def perceive(state: dict) -> dict:
    content = state["content"]
    hits = []
    for pattern in STRUCTURAL_PII_PATTERNS:
        for m in re.finditer(pattern, content):
            hits.append({"start": m.start(), "end": m.end()})
    state["structural_pii_hits"] = hits
    state["subject_known"] = bool(state.get("subject_name"))
    return state


def plan(state: dict) -> dict:
    return state


def act(state: dict) -> dict:
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


_NAME_TAILS = ("", "이", "이는", "이가", "이의", "이를", "이도", "이랑", "이와", "이한테", "이에게",
               "은", "는", "가", "의", "을", "를", "도", "랑", "와", "과", "한테", "에게")


def _is_subject_name_only(quote: str, subject_name: str | None) -> bool:
    if not subject_name:
        return False
    q = re.sub(r"\s+", "", quote or "")
    name = re.sub(r"\s+", "", subject_name)
    forms = [name] + ([name[1:]] if len(name) >= 3 else [])
    return any(q == form + tail for form in forms for tail in _NAME_TAILS)


_MENTION_WORDS = ("휴대폰번호", "전화번호", "연락처", "집주소", "주소", "번호", "이메일", "메일")
_RELATION_WORDS = ("부모님", "어머님", "아버님", "어머니", "아버지", "할머니", "할아버지", "보호자",
                   "엄마", "아빠", "가정", "집")
_PARTICLES = ("으로", "께서", "에게", "한테", "로", "께", "를", "을", "가", "이", "는", "은", "도", "의", "에", "와", "과")
_MENTION_ONLY = re.compile(
    r"^(?:(?:%s)(?:%s)?)*(?:(?:%s)(?:%s)?)+$" % (
        "|".join(_RELATION_WORDS), "|".join(_PARTICLES), "|".join(_MENTION_WORDS), "|".join(_PARTICLES))
)
_VALUE_HINT = re.compile(r"\d|@|아파트|빌라|번지|호수|[가-힣](?:시|구|군|동|읍|면|리)(?=[\s,.에]|$)")


def _is_mention_only(content: str, quote: str, subject_name: str | None) -> bool:
    q = re.sub(r"\s+", "", quote or "")
    if not q:
        return False
    if subject_name:
        name = re.sub(r"\s+", "", subject_name)
        forms = [name] + ([name[1:]] if len(name) >= 3 else [])
        heads = sorted((f + t for f in forms for t in _NAME_TAILS if t), key=len, reverse=True) + forms
        for h in heads:
            if q.startswith(h) and len(q) > len(h):
                q = q[len(h):]
                break
    if not _MENTION_ONLY.match(q):
        return False

    at = (content or "").find((quote or "").strip())
    if at < 0:
        return False
    start = max(content.rfind(".", 0, at), content.rfind("\n", 0, at)) + 1
    ends = [i for i in (content.find(".", at), content.find("\n", at)) if i >= 0]
    sentence = content[start:min(ends) if ends else len(content)]
    rest = sentence.replace(quote.strip(), " ")
    return not _VALUE_HINT.search(rest)


def reflect(state: dict) -> dict:
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

    if state["structural_pii_hits"]:
        issue_types.append("개인정보표현")
        evidence.extend(state["structural_pii_hits"])
        issues.extend({"type": "개인정보표현", **hit} for hit in state["structural_pii_hits"])
        verdict = "BLOCK"

    if state.get("llm_error"):
        if verdict == "BLOCK":
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
            continue

        if issue_type not in ATTRIBUTION_EXEMPT and not candidate.get("attributed_to_subject"):
            continue

        quote = candidate.get("evidence_quote", "")
        if issue_type == "개인정보표현" and _is_subject_name_only(quote, state.get("subject_name")):
            continue
        if issue_type == "개인정보표현" and _is_mention_only(content, quote, state.get("subject_name")):
            continue
        spans = spans_for_quotes(content, [quote])
        if not spans:
            continue

        issue_types.append(issue_type)
        evidence.extend(spans)
        issues.extend({"type": issue_type, **span} for span in spans)

        level = ISSUE_LEVEL[issue_type]
        if level == "BLOCK":
            verdict = "BLOCK"
        elif level == "REVIEW" and verdict != "BLOCK":
            verdict = "REVIEW"

    return done(verdict, list(set(issue_types)), issues)
