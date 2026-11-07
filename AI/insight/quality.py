# AI/insight/quality.py
"""
약한 인사이트 검사 — 모델을 부르지 않는다.

reflect 를 통과한 인사이트도, 다음 사람이 쓰기 어려운 것이 있다 (실데이터 dev 65개 중 9개).
그중 코드가 글자와 구조만 보고 확실히 알 수 있는 두 가지를 여기서 잡는다.

  기록에없는말     "그 결과는 기록되지 않았다" 처럼 기록이 없다는 사실을 글에 쓴 것.
                   다음 사람에게 쓸 정보가 아니고, 근거 claim 에도 없는 말이다.
                   문장만 빼면 살릴 수 있어서 문제 문장을 함께 돌려준다 (수정 루프의 피드백).
  한기관_대처없음  기관 간 비교도 아니고 대처도 없는 것. 한 기관에서 반응이 이랬다는 것만
                   남아, 다른 기관이 따라 할 것이 없다. 고칠 정보가 원래 없다.

관계없는 기록을 묶은 것, 상황이 뒤섞인 것은 의미를 읽어야 알 수 있어 여기서 다루지 않는다.
사람 표본 판정으로 잰다 (딥다이브 계획서).
"""

import re

NOT_IN_RECORD = "기록에없는말"
SINGLE_NO_SUPPORT = "한기관_대처없음"

#: "기록되지 않았다", "기록돼 있지 않다", "기록에는 없다", "기록이 없다", "기록은 없다",
#: "~한 기록도 있다", "확인되지 않았다", "알 수 없다". 실데이터 dev 4건이 모두 이 꼴이다.
_META = re.compile(
    r"기록(?:되지\s*않|돼\s*있지\s*않|에는?\s*없|이\s*없|은\s*없|도\s*있)"
    r"|확인되지\s*않|알\s*수\s*없"
)
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def _get(obj, key, default=None):
    """Insight 모델과 실행 결과 JSON(dict) 을 같은 방식으로 읽는다."""
    if isinstance(obj, dict):
        return obj.get(key, default)
    return getattr(obj, key, default)


def _texts(insight) -> list[tuple[str, str]]:
    """(어느 칸, 글) 목록. 대처는 칸 이름에 순서를 붙인다 (supports[0].result)."""
    out = [(k, _get(insight, k) or "") for k in ("situation", "behavior", "content")]
    for n, s in enumerate(_get(insight, "supports") or []):
        out.append((f"supports[{n}].action", _get(s, "action") or ""))
        out.append((f"supports[{n}].result", _get(s, "result") or ""))
    return out


def weak_reasons(insight) -> list[dict]:
    """
    약한 이유 목록. 비어 있으면 괜찮은 인사이트다.

    각 항목: {"kind": 종류, "field": 칸 이름, "sentence": 문제 문장}
    한기관_대처없음 은 특정 문장이 아니라 인사이트 전체의 문제라 field·sentence 가 비어 있다.
    """
    reasons = []
    for field, text in _texts(insight):
        for sentence in _SENTENCE_END.split(text.strip()):
            if sentence and _META.search(sentence):
                reasons.append({"kind": NOT_IN_RECORD, "field": field, "sentence": sentence})

    if not _get(insight, "cross_institution") and not (_get(insight, "supports") or []):
        reasons.append({"kind": SINGLE_NO_SUPPORT, "field": "", "sentence": ""})
    return reasons
