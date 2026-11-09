import re

NOT_IN_RECORD = "기록에없는말"
SINGLE_NO_SUPPORT = "한기관_대처없음"

_META = re.compile(
    r"기록(?:되지\s*않|돼\s*있지\s*않|에는?\s*없|이\s*없|은\s*없|도\s*있)"
    r"|확인되지\s*않|알\s*수\s*없"
)
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def _get(obj, key, default=None):
    if isinstance(obj, dict):
        return obj.get(key, default)
    return getattr(obj, key, default)


def _texts(insight) -> list[tuple[str, str]]:
    out = [(k, _get(insight, k) or "") for k in ("situation", "behavior", "content")]
    for n, s in enumerate(_get(insight, "supports") or []):
        out.append((f"supports[{n}].action", _get(s, "action") or ""))
        out.append((f"supports[{n}].result", _get(s, "result") or ""))
    return out


def weak_reasons(insight) -> list[dict]:
    reasons = []
    for field, text in _texts(insight):
        for sentence in _SENTENCE_END.split(text.strip()):
            if sentence and _META.search(sentence):
                reasons.append({"kind": NOT_IN_RECORD, "field": field, "sentence": sentence})

    if not _get(insight, "cross_institution") and not (_get(insight, "supports") or []):
        reasons.append({"kind": SINGLE_NO_SUPPORT, "field": "", "sentence": ""})
    return reasons
