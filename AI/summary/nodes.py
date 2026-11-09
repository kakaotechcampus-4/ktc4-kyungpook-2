import re

from .config import (
    CHECK_PROPER_NOUNS,
    CONTENT_FROM_CLAIMS_ONLY,
    COVERAGE_FROM_EVIDENCE,
    DROP_CLAIMS_WITHOUT_EVIDENCE,
    DROP_UNFOUND_QUOTES,
    REVIEW_ON_OTHER_NAMES,
)
from .llm import LlmError, ask_json, locate_quote
from .prompts import build_messages
from .state import SummaryState


def gather(state: SummaryState) -> dict:
    return {"by_id": {s.journal_entry_id: s.content for s in state["sources"]}}


def write(state: SummaryState) -> dict:
    if not state["sources"]:
        return {"llm_called": False, "llm_error": None, "raw_claims": []}

    try:
        result = ask_json(build_messages(state))
    except LlmError as exc:
        return {"llm_called": True, "llm_error": str(exc), "llm_failure": exc.kind, "raw_claims": []}

    raw = result.data.get("claims")
    if not isinstance(raw, list):
        return {
            "llm_called": True,
            "llm_error": "응답에 claims 배열이 없음",
            "llm_usage": result.usage,
            "raw_claims": [],
        }
    return {
        "llm_called": True,
        "llm_error": None,
        "llm_usage": result.usage,
        "raw_claims": raw,
    }


DATE_UNIT = re.compile(r"(\d+)\s*[년월일]")


def _numbers(text: str) -> set[int]:
    return {int(n) for n in re.findall(r"\d+", text or "")}


_ONES = {"한": 1, "첫": 1, "두": 2, "세": 3, "네": 4,
         "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9}
_TENS = {"열": 10, "스물": 20, "스무": 20, "서른": 30, "마흔": 40, "쉰": 50,
         "예순": 60, "일흔": 70, "여든": 80, "아흔": 90}
_BIG = {"백": 100, "천": 1000}

_VAGUE = ("한두", "두어", "두어세", "두세", "서너", "서넛", "네댓", "네다섯",
          "너덧", "대여섯", "예닐곱", "일여덟", "여남은", "열댓",
          "여러", "몇몇", "몇", "수십", "수백", "수천", "수")

_APPROX = r"(?:수)?(?:[일이삼사오육칠팔구])?(?:십|백|천)여"

_UNIT_GROUP = {
    "번": "회", "회": "회", "차례": "회",
    "바퀴": "바퀴", "걸음": "걸음", "칸": "칸",
    "개": "개", "장": "장", "명": "명", "층": "층", "쪽": "쪽",
    "분": "분", "시간": "시간", "초": "초",
}

NOT_QUANTITY = {"이", "그", "저", "요", "매", "지난", "이번", "저번", "요번"}

_KNOWN = "|".join([
    _APPROX,
    rf"(?:{'|'.join(_TENS)})(?:{'|'.join(_ONES)})",
    "|".join(sorted(_TENS, key=len, reverse=True)),
    "|".join(sorted(_VAGUE, key=len, reverse=True)),
    "|".join(sorted(_ONES, key=len, reverse=True)),
    "|".join(_BIG),
])

_UNIT_RE = "|".join(sorted(_UNIT_GROUP, key=len, reverse=True))

_TAIL = r"(?=[을를이가은는에의와과도만씩째쯤적까부보마처밖\s,.)\]]|$)"

COUNT = re.compile(rf"(?<![0-9가-힣])(\d+|{_KNOWN})\s*({_UNIT_RE})" + _TAIL)

_JOSA_BEFORE = r"(?<=[을를이가은는에의도와과로])"
COUNT_LOOSE = re.compile(
    rf"(?:(?<![0-9가-힣])|{_JOSA_BEFORE})(\d+여|\d+|{_KNOWN})\s*({_UNIT_RE})" + _TAIL
)

NEGATION = re.compile(r"않|못[하했한할함해]|(?<![가-힣])못 |없")

CLAUSE_END = re.compile(r"았지만|었지만|했지만|였지만|으나|는데|[.!?\n]")


def _count_value(token: str) -> str | None:
    if token.isdigit():
        return token
    if token in NOT_QUANTITY:
        return None
    if token.endswith("여"):
        return token
    for tens, value in _TENS.items():
        if token.startswith(tens):
            rest = token[len(tens):]
            if not rest:
                return str(value)
            if rest in _ONES:
                return str(value + _ONES[rest])
            return token
    if token in _ONES:
        return str(_ONES[token])
    if token in _BIG:
        return str(_BIG[token])
    return token


def _clause_from(text: str, start: int) -> str:
    end = CLAUSE_END.search(text, start)
    return text[start:end.start()] if end else text[start:]


def _counts(text: str, loose: bool = False) -> set[tuple[str, str]]:
    text = text or ""
    pairs = set()
    for m in (COUNT_LOOSE if loose else COUNT).finditer(text):
        token, unit = m.group(1), m.group(2)
        if token in ("한", "1") and text[m.end():m.end() + 1] == "씩":
            continue
        if unit == "쪽" and not token.isdigit() and not re.search(r"\s", m.group(0)):
            continue
        if text[m.end():m.end() + 1] == "도" and NEGATION.search(_clause_from(text, m.end())):
            continue
        if loose and token.endswith("여") and token[:-1].isdigit():
            token = token[:-1]
        value = _count_value(token)
        if value is None:
            continue
        pairs.add((value, _UNIT_GROUP[unit]))
    return pairs


def _numbers_outside_dates(text: str, entry_date: str) -> set[int]:
    allowed = _numbers(entry_date)
    blanked = DATE_UNIT.sub(
        lambda m: " " if int(m.group(1)) in allowed else m.group(0), text or ""
    )
    return _numbers(blanked)


def _facts_grounded(text: str, evidence: list[dict], state: SummaryState) -> bool:
    quotes = " ".join(e["quote"] for e in evidence)

    left = _numbers_outside_dates(text, state.get("entry_date", ""))
    if left - _numbers(quotes):
        return False

    return not (_counts(text) - _counts(quotes, loose=True))


def ground(state: SummaryState) -> dict:
    by_id = state["by_id"]
    claims: list[dict] = []
    dropped_evidence = 0
    dropped_claims: list[str] = []
    dropped_detail: list[dict] = []

    for raw in state.get("raw_claims") or []:
        if not isinstance(raw, dict):
            continue
        text = raw.get("text")
        if not isinstance(text, str) or not text.strip():
            continue

        kept: list[dict] = []
        for item in raw.get("evidence") or []:
            if not isinstance(item, dict):
                continue
            entry_id = item.get("journal_entry_id")
            quote = item.get("quote")
            if isinstance(entry_id, bool) or not isinstance(entry_id, int):
                dropped_evidence += 1
                continue
            span = locate_quote(by_id[entry_id], quote) if entry_id in by_id else None
            if span is None:
                if DROP_UNFOUND_QUOTES:
                    dropped_evidence += 1
                continue
            kept.append(
                {"journal_entry_id": entry_id, "quote": quote, "span": span}
            )

        if DROP_CLAIMS_WITHOUT_EVIDENCE and not kept:
            dropped_claims.append(text)
            dropped_detail.append({"text": text, "reason": "인용_못찾음",
                                   "evidence": raw.get("evidence") or []})
            continue

        if CHECK_PROPER_NOUNS and kept and not _facts_grounded(text, kept, state):
            dropped_claims.append(text)
            dropped_detail.append({"text": text, "reason": "숫자_불일치",
                                   "evidence": raw.get("evidence") or []})
            continue

        claims.append({"text": text.strip(), "evidence": kept})

    return {
        "claims": claims,
        "dropped_evidence": dropped_evidence,
        "dropped_claims": dropped_claims,
        "dropped_detail": dropped_detail,
    }


def assemble(state: SummaryState) -> dict:
    claims = state.get("claims") or []

    content = " ".join(c["text"] for c in claims) if CONTENT_FROM_CLAIMS_ONLY else ""

    if COVERAGE_FROM_EVIDENCE:
        covered = {e["journal_entry_id"] for c in claims for e in c["evidence"]}
    else:
        covered = set()

    reasons: list[str] = []
    if REVIEW_ON_OTHER_NAMES:
        leaked = [n for n in (state.get("other_child_names") or []) if n and n in content]
        if leaked:
            reasons.append("다른아동이름")

    return {
        "content": content,
        "covered_entry_ids": sorted(covered),
        "uncovered_entry_ids": sorted(set(state["by_id"]) - covered),
        "needs_review": bool(reasons),
        "review_reasons": reasons,
    }
