"""
본문에서 아동 이름을 찾는 문자열 유틸.

여기서 LLM 을 쓰지 않는다. 이름이 그대로 적힌 기록은 코드로 끝내고,
애매한 것만 LLM 으로 넘기기 위한 사전 작업이다.
정확도보다 "후보를 2~3명으로 줄이는 것" 이 목표라 완벽할 필요는 없다.

offset 은 모두 Python str 인덱스(유니코드 코드포인트) 기준이다.
"""

from dataclasses import dataclass
from difflib import SequenceMatcher

from .config import (
    FUZZY_MIN_RATIO,
    MATCH_GIVEN_NAME,
    NAME_SUFFIX_CHARS,
    REQUIRE_EXACT_NAME_BOUNDARY,
    REQUIRE_NAME_BOUNDARY,
)


@dataclass
class NameHit:
    """본문에서 이름이 발견된 지점 하나."""

    child_id: int
    name: str
    start: int
    end: int
    #: 본문에 이름이 그대로 있고 앞뒤가 이름 경계인지.
    #: 이 값이 True 일 때만 "이름이 등장했다" 고 본다.
    exact: bool
    #: 유사도 0~1. exact 면 1.0
    ratio: float
    #: 이름이 그대로 있었지만 단어의 일부였는지 ('은하수' 안의 '은하').
    #: 후보로는 남기되 자동 확정 경로에는 넣지 않는다.
    partial: bool = False
    #: 성을 뗀 이름으로 걸렸는지 ('백지안' 을 '지안이가' 로).
    given_name: bool = False


def _is_hangul_block(text: str) -> bool:
    """한글 음절로만 이루어졌는지. 공백·구두점이 섞인 구간을 이름 후보에서 뺀다."""
    return all("가" <= ch <= "힣" for ch in text)


def _has_name_boundary(content: str, end: int) -> bool:
    """
    구간 끝이 이름의 끝으로 말이 되는지.

    한글 이름 뒤에는 조사("정하윤이"), 호칭("정하윤님"), 또는 공백·구두점이
    온다. 그 밖의 음절이 붙어 있으면 단어 중간을 잘라낸 것이다 —
    "정교하게" 에서 '정교하' 를 떼어낸 것처럼.
    """
    if end >= len(content):
        return True

    nxt = content[end]
    if not ("가" <= nxt <= "힣"):
        # 공백·구두점·숫자·영문은 그대로 경계다
        return True

    return nxt in NAME_SUFFIX_CHARS


def _has_prefix_boundary(content: str, start: int) -> bool:
    """
    구간 앞이 이름의 시작으로 말이 되는지.

    앞 글자가 한글 음절이면 단어 중간을 잘라낸 것이다 — '김은하' 에서 '은하'
    를 떼어낸 것처럼. 문장 시작·공백·구두점은 경계다.
    """
    if start == 0:
        return True
    return not ("가" <= content[start - 1] <= "힣")


def find_exact(content: str, name: str) -> list[tuple[int, int]]:
    """이름이 그대로 등장하는 모든 위치. 같은 이름이 여러 번 나올 수 있다."""
    spans: list[tuple[int, int]] = []
    start = 0
    while True:
        i = content.find(name, start)
        if i < 0:
            return spans
        spans.append((i, i + len(name)))
        start = i + 1


def find_fuzzy(
    content: str,
    name: str,
    min_ratio: float = FUZZY_MIN_RATIO,
    skip: list[tuple[int, int]] | None = None,
) -> list[tuple[int, int, float]]:
    """
    이름과 같은 길이의 창을 본문 위로 밀면서 유사도를 잰다.

    '김민준' → '김민쥰' 같은 오타를 잡기 위한 것이다.
    길이가 달라지는 오타('김민주니')는 놓치지만, 후보 축소용이라 감수한다.
    """
    n = len(name)
    if n == 0 or len(content) < n:
        return []

    skip = skip or []
    raw: list[tuple[int, int, float]] = []

    for i in range(len(content) - n + 1):
        window = content[i : i + n]
        if not _is_hangul_block(window):
            continue
        # 이미 정확 일치로 잡힌 구간은 건너뛴다
        if any(s <= i < e for s, e in skip):
            continue
        ratio = SequenceMatcher(None, name, window).ratio()
        if ratio < min_ratio:
            continue
        # 오타 후보는 경계까지 맞아야 인정한다. 정확 일치와 달리 근거가
        # 유사도뿐이라, 단어 중간을 잘라낸 것과 구별할 방법이 이것뿐이다.
        if REQUIRE_NAME_BOUNDARY and not _has_name_boundary(content, i + n):
            continue
        raw.append((i, i + n, ratio))

    return _dedupe_overlaps(raw)


def _dedupe_overlaps(
    spans: list[tuple[int, int, float]],
) -> list[tuple[int, int, float]]:
    """겹치는 구간 중 유사도가 가장 높은 것만 남긴다."""
    kept: list[tuple[int, int, float]] = []
    for span in sorted(spans, key=lambda s: s[2], reverse=True):
        s, e, _ = span
        if any(not (e <= ks or s >= ke) for ks, ke, _ in kept):
            continue
        kept.append(span)
    return sorted(kept, key=lambda s: s[0])


def find_name_hits(content: str, roster: list) -> list[NameHit]:
    """명부의 모든 아이에 대해 본문을 훑어 발견 지점을 모은다."""
    hits: list[NameHit] = []

    for entry in roster:
        exact_spans = find_exact(content, entry.name)
        for s, e in exact_spans:
            # 문자열이 들어 있다는 것만으로는 이름이 아니다.
            # 앞뒤가 모두 경계일 때만 "이름이 등장했다" 로 인정한다.
            on_boundary = not REQUIRE_EXACT_NAME_BOUNDARY or (
                _has_prefix_boundary(content, s) and _has_name_boundary(content, e)
            )
            hits.append(
                NameHit(
                    entry.child_id,
                    entry.name,
                    s,
                    e,
                    exact=on_boundary,
                    ratio=1.0,
                    partial=not on_boundary,
                )
            )

        # 성을 뗀 이름으로 부른 경우. 전체 이름이 안 걸렸을 때만 본다.
        if MATCH_GIVEN_NAME and not exact_spans and len(entry.name) >= 3:
            given = entry.name[1:]
            for s, e in find_exact(content, given):
                if _has_prefix_boundary(content, s) and _has_name_boundary(content, e):
                    hits.append(
                        NameHit(
                            entry.child_id,
                            entry.name,
                            s,
                            e,
                            exact=False,
                            ratio=1.0,
                            given_name=True,
                        )
                    )

        for s, e, ratio in find_fuzzy(content, entry.name, skip=exact_spans):
            hits.append(
                NameHit(entry.child_id, entry.name, s, e, exact=False, ratio=ratio)
            )

    return sorted(hits, key=lambda h: h.start)
