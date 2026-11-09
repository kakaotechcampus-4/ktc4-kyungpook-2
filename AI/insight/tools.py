import datetime as dt
import re

TOOL_SPECS = [{
    "type": "function",
    "name": "find_claims",
    "description": ("같은 아이의 지난 승인된 관찰 문장 중 상황이 비슷한 것을 찾는다. "
                    "이번 기간 근거가 한 기관뿐일 때, 다른 기관에서 같은 상황이 있었는지 볼 때 쓴다."),
    "parameters": {
        "type": "object",
        "properties": {
            "situation": {"type": "string", "description": "찾을 상황 (예: 차량 탑승을 거부함)"},
            "weeks_back": {"type": "integer", "description": "이번 기간 시작일로부터 몇 주 전까지 (1~12)"},
        },
        "required": ["situation"],
    },
}]

_HANGUL = re.compile(r"[가-힣]+")


def _bigrams(text: str) -> set[str]:
    grams = set()
    for word in _HANGUL.findall(text or ""):
        grams.update(word[i:i + 2] for i in range(len(word) - 1))
    return grams


def find_claims(history: list[dict], situation: str, *, weeks_back=None, before: str | None = None,
                limit: int = 5, scores: dict | None = None) -> list[dict]:
    want = _bigrams(situation)
    if not want:
        return []
    try:
        weeks = min(max(int(weeks_back), 1), 12)
    except (TypeError, ValueError):
        weeks = 8
    start = end = None
    if before:
        end = dt.date.fromisoformat(before)
        start = end - dt.timedelta(weeks=weeks)

    scored = []
    for c in history:
        if end is not None:
            day = dt.date.fromisoformat(c["entry_date"])
            if not (start <= day < end):
                continue
        score = len(want & _bigrams(c["text"]))
        if score >= 1:
            scored.append((score, c["entry_date"], c))
    scored.sort(key=lambda x: (-x[0], x[1]))
    if scores is not None:
        scores.update({c["claim_id"]: s for s, _, c in scored[:limit]})
    return [
        {"claim_id": c["claim_id"], "entry_date": c["entry_date"],
         "institution_type": c["institution_type"], "text": c["text"]}
        for _, _, c in scored[:limit]
    ]
