# AI/evals/scripts/feedback/kinds.py
"""
교사가 AI 초안을 **어떻게** 고쳤는지 가른다. **모델을 부르지 않는다.**

교사에게 "왜 고쳤냐" 를 묻지 않는 대신, 초안과 최종본의 글자를 맞대어 종류를 센다.
물어서 받는 편이 정확하지만 Gate 1 은 선생님이 매일 쓰는 화면이라 단계를 늘리지 않는다
(`README.md` 의 "묻지 않는 이유").

## 액션은 저장돼 있지 않다 — `source` 로 되살린다

Gate 1 카드(유지·수정·삭제·추가)는 적용되고 버려진다. 남는 것은 최종 `claims` 뿐인데,
문장마다 `source` 가 붙어 있어 거기서 되살릴 수 있다 (`service/approval/Gate1Cards.java`).

    source: AI       유지      초안 그대로. 근거도 같다
    source: EDITED   수정      **근거는 초안 것을 그대로 쓴다** ← 짝을 맞추는 열쇠
    source: TEACHER  추가      일지가 없어 journal_entry_id 가 null 이다
    초안에만 있음     삭제

수정은 글만 바꾸고 근거를 건드리지 않으므로, **근거로 초안과 최종본을 짝지을 수 있다.**
글로 짝지으면 많이 고친 문장을 놓친다.
"""

import re
from collections import Counter

#: 고친 종류. 순서가 곧 판정 순서다 — 위에서부터 먼저 걸린다.
NUMBER = "숫자"
TONE = "말투"
SHORTER = "줄임"
LONGER = "늘림"
OTHER = "기타"

KEEP, EDIT, DELETE, ADD = "유지", "수정", "삭제", "추가"

#: 한글 수사도 숫자다. "세 번" 을 "다섯 번" 으로 바꾼 것이 가장 중요한 신호인데
#: 아라비아 숫자만 보면 통째로 놓친다. summary/nodes.py 의 _count_value 와 같은 생각이다.
_SINO = {"영": 0, "일": 1, "이": 2, "삼": 3, "사": 4, "오": 5, "육": 6, "칠": 7, "팔": 8, "구": 9, "십": 10}
_NATIVE = {"한": 1, "두": 2, "세": 3, "네": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9, "열": 10,
           "하나": 1, "둘": 2, "셋": 3, "넷": 4}
_DIGITS = re.compile(r"\d+")
_WORD = re.compile(r"\S+")


def numbers(text: str) -> Counter:
    """글에 나온 수량. 아라비아 숫자와 한글 수사를 같은 값으로 센다."""
    found = Counter(int(n) for n in _DIGITS.findall(text or ""))
    for word, value in {**_SINO, **_NATIVE}.items():
        hits = len(re.findall(rf"(?<![가-힣]){re.escape(word)}(?=\s*(?:번|개|명|회|차례|시간|분|쪽|권|마디))", text or ""))
        if hits:
            found[value] += hits
    return found


def _words(text: str) -> list[str]:
    return _WORD.findall((text or "").strip().rstrip(".!?"))


def edit_kind(before: str, after: str) -> str:
    """
    수정 한 건의 종류.

    **숫자를 맨 먼저 본다.** 환각이 가장 눈에 띄게 드러나는 자리이기도 하고, 숫자가 바뀐
    수정이 길이로는 "말투" 로 보일 수 있어서다 ("세 번" → "여러 번" 은 끝 낱말만 다르다).

    ⚠️ 여기서 나오는 것은 **무엇이 바뀌었나**지 **왜 바꿨나**가 아니다.
    "세 번 → 다섯 번" 이 사실을 바로잡은 것인지 표현을 다듬은 것인지 글자는 말해 주지 않는다.
    그 둘을 가르려면 교사에게 물어야 한다 (README "이 분류가 못 하는 것").
    """
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


def _key(claim: dict) -> tuple | None:
    """근거로 만든 짝 열쇠. 교사가 쓴 문장(일지 없음)은 짝지을 초안이 없어 None 이다."""
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
    """
    초안과 최종본을 짝지어 문장마다 무슨 일이 있었는지 돌려준다.

    각 항목: {action, kind, before, after, chars}
      - `kind` 는 수정일 때만 채운다
      - `chars` 는 글자 수 차이 (음수면 줄어든 것)

    ⚠️ **삭제에는 교사가 지운 것과 근거 검사가 버린 것이 섞여 있다.**
    `raw_response` 는 모델이 낸 원본이라 `ground` 가 버린 문장도 들어 있고, 그 둘을 이 행만
    보고 가를 수 없다. 실데이터에서 버린 문장이 575 중 2건(0.35%)이라 거의 교사 삭제지만,
    **0 이라고 적지는 않는다** (`AI/summary/CRITERIA.md §2-b`).
    """
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
            out.append({"action": EDIT, "kind": edit_kind(before or "", text),
                        "before": before, "after": text,
                        "chars": len(text) - len(before or "")})
        else:
            out.append({"action": KEEP, "kind": None, "before": before, "after": text, "chars": 0})

    for key, claim in by_key.items():
        if key in taken:
            continue
        before = (claim.get("text") or "").strip()
        out.append({"action": DELETE, "kind": None, "before": before, "after": None, "chars": -len(before)})
    return out


def tally(rows: list[dict]) -> dict:
    """여러 요약의 비교 결과를 한 장으로 접는다."""
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
        #: 같은 종류로 두 번 이상 고친 것. "다음에 고칠 곳" 이다 (멘토 리뷰 표)
        "반복오류": {k: n for k, n in kinds.most_common() if n >= 2},
        "수정량평균": round(sum(abs(r["chars"]) for r in edited) / len(edited), 1) if edited else None,
    }
