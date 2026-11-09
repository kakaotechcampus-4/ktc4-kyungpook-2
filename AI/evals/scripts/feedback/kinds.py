import difflib
import re
from collections import Counter

NUMBER = "숫자"
TONE = "말투"
SHORTER = "줄임"
LONGER = "늘림"
OTHER = "기타"

KEEP, EDIT, DELETE, ADD = "유지", "수정", "삭제", "추가"

_SINO = {"영": 0, "일": 1, "이": 2, "삼": 3, "사": 4, "오": 5, "육": 6, "칠": 7, "팔": 8, "구": 9, "십": 10}
_NATIVE = {"한": 1, "두": 2, "세": 3, "네": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9, "열": 10,
           "하나": 1, "둘": 2, "셋": 3, "넷": 4}
_DIGITS = re.compile(r"\d+")
_WORD = re.compile(r"\S+")


def numbers(text: str) -> Counter:
    found = Counter(int(n) for n in _DIGITS.findall(text or ""))
    for word, value in {**_SINO, **_NATIVE}.items():
        hits = len(re.findall(rf"(?<![가-힣]){re.escape(word)}(?=\s*(?:번|개|명|회|차례|시간|분|쪽|권|마디))", text or ""))
        if hits:
            found[value] += hits
    return found


def _words(text: str) -> list[str]:
    return _WORD.findall((text or "").strip().rstrip(".!?"))


def edit_kind(before: str, after: str) -> str:
    if numbers(before) != numbers(after):
        return NUMBER

    head_b, head_a = _words(before)[:-1], _words(after)[:-1]
    if head_b and head_b == head_a:
        return TONE

    if len(after) < len(before):
        return SHORTER
    if len(after) > len(before):
        return LONGER
    return OTHER


MIN_FRAGMENT = 5

_TAIL = re.compile(r"(?:으며|며|으나|나|지만|고|어서|아서|여서|하여|해서)$")


def fragments(before: str, after: str) -> tuple[list[str], list[str]]:
    sm = difflib.SequenceMatcher(None, before or "", after or "", autojunk=False)
    out: tuple[list[str], list[str]] = ([], [])
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("delete", "replace"):
            piece = (before or "")[i1:i2].strip(" ,.·")
            if len(piece) >= MIN_FRAGMENT:
                out[0].append(piece)
        if tag in ("insert", "replace"):
            piece = (after or "")[j1:j2].strip(" ,.·")
            if len(piece) >= MIN_FRAGMENT:
                out[1].append(piece)
    return out


def _key(claim: dict) -> tuple | None:
    items = []
    for e in claim.get("evidence") or []:
        if not isinstance(e, dict):
            continue
        entry = e.get("journal_entry_id")
        if entry is None:
            return None
        items.append((entry, (e.get("quote") or "").strip()))
    return tuple(items) or None


def compare(draft: list[dict], final: list[dict]) -> list[dict]:
    taken: set[tuple] = set()
    by_key: dict[tuple, dict] = {}
    for claim in draft:
        key = _key(claim)
        if key is not None:
            by_key.setdefault(key, claim)

    out: list[dict] = []
    for claim in final:
        source = (claim.get("source") or "AI").upper()
        text = (claim.get("text") or "").strip()
        if source == "TEACHER":
            out.append({"action": ADD, "kind": None, "before": None, "after": text, "chars": len(text)})
            continue

        key = _key(claim)
        origin = by_key.get(key) if key is not None else None
        if key is not None:
            taken.add(key)
        before = (origin.get("text") or "").strip() if origin else None

        if source == "EDITED" or (before is not None and before != text):
            removed, added = fragments(before or "", text)
            out.append({"action": EDIT, "kind": edit_kind(before or "", text),
                        "before": before, "after": text,
                        "chars": len(text) - len(before or ""),
                        "뺀말": removed, "넣은말": added})
        else:
            out.append({"action": KEEP, "kind": None, "before": before, "after": text, "chars": 0})

    for key, claim in by_key.items():
        if key in taken:
            continue
        before = (claim.get("text") or "").strip()
        out.append({"action": DELETE, "kind": None, "before": before, "after": None, "chars": -len(before)})
    return out


def _stem(fragment: str) -> str:
    return _TAIL.sub("", fragment.strip()) or fragment.strip()


_AGAIN = re.compile(r"#\d+$")


def _case(summary_id) -> str:
    return _AGAIN.sub("", str(summary_id))


def _group(pairs) -> list[dict]:
    forms: dict[str, Counter] = {}
    cases: dict[str, set] = {}
    for piece, summary_id in pairs:
        key = _stem(piece)
        forms.setdefault(key, Counter())[piece] += 1
        cases.setdefault(key, set()).add(_case(summary_id))
    order = sorted(forms, key=lambda k: (-len(cases[k]), -sum(forms[k].values())))
    return [{"말": forms[k].most_common(1)[0][0], "기록": len(cases[k]), "번": sum(forms[k].values())}
            for k in order[:8]]


def tally(rows: list[dict]) -> dict:
    actions = Counter(r["action"] for r in rows)
    kinds = Counter(r["kind"] for r in rows if r["kind"])
    edited = [r for r in rows if r["action"] == EDIT]
    total = sum(actions.values())
    return {
        "문장": total,
        "액션": dict(actions),
        "유지비율": round(actions[KEEP] / total, 4) if total else None,
        "손댄비율": round((total - actions[KEEP]) / total, 4) if total else None,
        "종류": dict(kinds.most_common()),
        "반복오류": {k: n for k, n in kinds.most_common() if n >= 2},
        "수정량평균": round(sum(abs(r["chars"]) for r in edited) / len(edited), 1) if edited else None,
        "자주뺀말": _group((f, r.get("summary_id")) for r in edited for f in r.get("뺀말") or []),
        "자주넣은말": _group((f, r.get("summary_id")) for r in edited for f in r.get("넣은말") or []),
        "손댄기록": len({_case(r["summary_id"]) for r in rows
                         if r["action"] != KEEP and r.get("summary_id") is not None}),
        "기록": len({_case(r["summary_id"]) for r in rows if r.get("summary_id") is not None}),
    }
