from dataclasses import dataclass
from difflib import SequenceMatcher

from .config import (
    FUZZY_MIN_RATIO,
    FUZZY_REQUIRE_PARTICLE,
    FUZZY_REQUIRE_SAME_SURNAME,
    MATCH_GIVEN_NAME,
    NAME_SUFFIX_CHARS,
    REQUIRE_EXACT_NAME_BOUNDARY,
    REQUIRE_NAME_BOUNDARY,
)


@dataclass
class NameHit:

    child_id: int
    name: str
    start: int
    end: int
    exact: bool
    ratio: float
    partial: bool = False
    given_name: bool = False


def _is_hangul_block(text: str) -> bool:
    return all("가" <= ch <= "힣" for ch in text)


def _has_name_boundary(content: str, end: int) -> bool:
    if end >= len(content):
        return True

    nxt = content[end]
    if not ("가" <= nxt <= "힣"):
        return True

    return nxt in NAME_SUFFIX_CHARS


def _followed_by_particle(content: str, end: int) -> bool:
    if end >= len(content):
        return False
    nxt = content[end]
    return ("가" <= nxt <= "힣") and nxt in NAME_SUFFIX_CHARS


def _has_prefix_boundary(content: str, start: int) -> bool:
    if start == 0:
        return True
    return not ("가" <= content[start - 1] <= "힣")


def find_exact(content: str, name: str) -> list[tuple[int, int]]:
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
    n = len(name)
    if n == 0 or len(content) < n:
        return []

    skip = skip or []
    raw: list[tuple[int, int, float]] = []

    for i in range(len(content) - n + 1):
        window = content[i : i + n]
        if not _is_hangul_block(window):
            continue
        if any(s <= i < e for s, e in skip):
            continue
        if FUZZY_REQUIRE_SAME_SURNAME and window[0] != name[0]:
            continue
        ratio = SequenceMatcher(None, name, window).ratio()
        if ratio < min_ratio:
            continue
        if REQUIRE_NAME_BOUNDARY and not _has_name_boundary(content, i + n):
            continue
        if FUZZY_REQUIRE_PARTICLE and not _followed_by_particle(content, i + n):
            continue
        raw.append((i, i + n, ratio))

    return _dedupe_overlaps(raw)


def _dedupe_overlaps(
    spans: list[tuple[int, int, float]],
) -> list[tuple[int, int, float]]:
    kept: list[tuple[int, int, float]] = []
    for span in sorted(spans, key=lambda s: s[2], reverse=True):
        s, e, _ = span
        if any(not (e <= ks or s >= ke) for ks, ke, _ in kept):
            continue
        kept.append(span)
    return sorted(kept, key=lambda s: s[0])


def find_name_hits(content: str, roster: list) -> list[NameHit]:
    hits: list[NameHit] = []

    for entry in roster:
        exact_spans = find_exact(content, entry.name)
        for s, e in exact_spans:
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
