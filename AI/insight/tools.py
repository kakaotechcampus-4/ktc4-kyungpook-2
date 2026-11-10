# AI/insight/tools.py
"""
인사이트 에이전트가 부를 수 있는 도구 (딥다이브 3). 모델을 부르지 않는다.

find_claims: 같은 아이의 지난 승인된 claim 중 상황이 비슷한 것을 찾는다.
  - 찾는 범위는 BE 가 넣은 history_claims (perceive 에서 동의 기관만 남김) 뿐이다.
    모델은 무엇을 찾을지(상황 낱말, 몇 주 전까지)만 정하고, 어디서 찾을지는 정하지 않는다.
  - 원문 인용(evidence.quote)은 돌려주지 않는다. 다른 아이 이름이 남아 있다 (CRITERIA §2).

비슷함은 한글 두 글자 조각(바이그램)이 얼마나 겹치는지로 센다. 형태소 분석기 없이
"차량 탑승" 과 "차량에 탑승하지" 를 같은 상황으로 묶을 수 있다.
"""

import datetime as dt
import re

#: 모델에게 보여주는 도구 설명 (/v1/responses 의 function tool 형식).
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
    """
    history 안에서 situation 과 겹치는 조각이 많은 claim 을 최대 limit 개.

    before(이번 기간 시작일) 이전 것만 보고, weeks_back 주 이내로 자른다 (1~12, 기본 8).
    겹치는 조각이 하나도 없으면 주지 않는다. 같은 상황인지는 모델이 고르고, 고른 뒤에는
    코드가 성립 조건으로 다시 본다 — 여기서 너무 좁히면 "차량" 과 "차량 앞에서" 를 놓친다.
    """
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
    if scores is not None:  # 평가용 — 돌려준 기록의 점수. 모델에게는 보이지 않는다 (#373 리뷰 3)
        scores.update({c["claim_id"]: s for s, _, c in scored[:limit]})
    return [
        {"claim_id": c["claim_id"], "entry_date": c["entry_date"],
         "institution_type": c["institution_type"], "text": c["text"]}
        for _, _, c in scored[:limit]
    ]
