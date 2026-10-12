# AI/summary/nodes.py
"""
Summary Agent 의 노드들.

    gather → write → ground → assemble

모델이 일하는 것은 write 하나뿐이다. 나머지 셋은 코드가 한다.
판정 기준은 CRITERIA.md 에 있고, 여기 코드는 그 §2 "코드가 거르는 네 단계" 를
그대로 옮긴 것이다.

    ① 인용을 원문에서 찾는다           못 찾으면 그 근거를 버린다
    ② 근거 0 개가 된 문장을 버린다
    ③ 고유명사가 근거에 없으면 버린다
    ④ 남은 claims 로 본문을 잇는다      covered 도 코드가 센다
"""

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


# ── ① gather ───────────────────────────────────────────────────


def gather(state: SummaryState) -> dict:
    """
    일지를 id 로 색인한다.

    인용을 **그 일지 안에서만** 찾기 위해서다. 전부 이어 붙여 놓고 아무 데서나
    찾으면, 모델이 A 일지 내용이라고 말한 문장을 B 일지에서 찾아 통과시키게 된다.
    근거가 어느 기록에서 왔는지가 요약의 전부라 그걸 섞으면 안 된다.
    """
    return {"by_id": {s.journal_entry_id: s.content for s in state["sources"]}}


# ── ② write ────────────────────────────────────────────────────


def write(state: SummaryState) -> dict:
    """
    모델에게 claims 를 받는다. 본문도 좌표도 받지 않는다 (prompts.py 참고).

    호출이 실패해도 여기서 터뜨리지 않는다. llm_error 만 남기고 run_summary 가
    503 으로 바꾼다 — 노드가 예외를 던지면 그래프 중간 상태가 사라진다.
    """
    if not state["sources"]:
        return {"llm_called": False, "llm_error": None, "raw_claims": []}

    try:
        result = ask_json(build_messages(state))
    except LlmError as exc:
        return {"llm_called": True, "llm_error": str(exc), "raw_claims": []}

    raw = result.data.get("claims")
    return {
        "llm_called": True,
        "llm_error": None,
        "llm_usage": result.usage,
        "raw_claims": raw if isinstance(raw, list) else [],
    }


# ── ③ ground ───────────────────────────────────────────────────


#: 문장 안에서 날짜를 가리키는 숫자. "10월 5일", "2026년" 처럼 단위가 붙은 것만 본다.
#: "7번", "오전 10시" 는 여기 걸리지 않는다 — 그것은 관찰한 내용이다.
DATE_UNIT = re.compile(r"(\d+)\s*[년월일]")


def _numbers(text: str) -> set[int]:
    """숫자를 값으로 비교한다. "04" 와 "4" 가 다른 수로 취급되면 안 된다."""
    return {int(n) for n in re.findall(r"\d+", text or "")}


def _date_numbers(text: str) -> set[int]:
    """날짜 표현에 쓰인 숫자만. 연·월·일 단위가 붙은 것이다."""
    return {int(n) for n in DATE_UNIT.findall(text or "")}


def _facts_grounded(text: str, evidence: list[dict], state: SummaryState) -> bool:
    """
    문장에 나온 숫자가 근거에 있는지 본다.

    환각이 가장 눈에 띄게 드러나는 자리다 — 모델은 "세 번" 을 "다섯 번" 으로
    바꾼다.

    기관명 대조는 더 하지 않는다. 묶음이 한 기관 안에서만 이뤄지면서
    "센터 일을 학교 것으로 옮겨 적는" 경우 자체가 입력에서 사라졌다.

    ⚠️ **사람 이름은 검사하지 못한다.** SummaryInput 에 명부가 없어서 어떤
    글자가 다른 아이 이름인지 알 방법이 없다. 매칭의 mentioned_child_ids 를
    입력으로 받으면 잡을 수 있다 (schema.md §10.2-11 과 연결된다).
    """
    quotes = " ".join(e["quote"] for e in evidence)

    # 날짜를 가리키는 숫자는 입력으로 받은 값이라 지어낸 것이 아니다. 다만
    # **그 문장에서 날짜로 쓰였을 때만** 면제한다 — 단위(년·월·일)가 붙어 있고
    # 묶음 날짜와 값이 같아야 한다.
    #
    # 예전에는 entry_date 의 숫자를 통째로 면제했다. 2026-10-07 에 쓴 요약이면
    # "공을 7번 주고받았다", "10번", "2026번" 이 전부 통과했다 — 날짜 숫자가
    # 관찰 횟수의 근거가 돼 버린 것이다 (2026-10-08 멘토 리뷰).
    date_ok = _date_numbers(text) & _numbers(state.get("entry_date", ""))

    # 나머지 숫자 — 횟수·시간·층수 같은 관찰 내용은 근거 인용에 그대로 있어야 한다.
    return not (_numbers(text) - _numbers(quotes) - date_ok)


def ground(state: SummaryState) -> dict:
    """
    모델이 쓴 문장을 원문에 대조한다. 여기가 환각을 막는 자리다.

    남는 것만 claims 가 되고, 버린 것은 세어서 남긴다 — 얼마나 버렸는지가
    모델 품질 지표다 (CRITERIA.md §6).
    """
    by_id = state["by_id"]
    claims: list[dict] = []
    dropped_evidence = 0
    dropped_claims: list[str] = []

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
            # bool 은 int 의 하위 타입이라 True 가 1 로 통과한다. 먼저 걸러낸다.
            if isinstance(entry_id, bool) or not isinstance(entry_id, int):
                dropped_evidence += 1
                continue
            # ① 그 일지 안에서만 찾는다. 없는 일지를 댔으면 그것도 버린다.
            #
            # 못 찾으면 플래그와 무관하게 버린다. Evidence.span 이 필수라 빈 채로
            # 만들면 ValidationError 가 나고, BE 의 근거 재검사도 span 없는 근거를
            # 요약 전체 실패로 본다 (#146). DROP_UNFOUND_QUOTES 는 이제 "버린 것을
            # 세는가" 만 정한다.
            span = locate_quote(by_id[entry_id], quote) if entry_id in by_id else None
            if span is None:
                if DROP_UNFOUND_QUOTES:
                    dropped_evidence += 1
                continue
            kept.append(
                {"journal_entry_id": entry_id, "quote": quote, "span": span}
            )

        # ② 근거가 하나도 안 남은 문장은 버린다.
        if DROP_CLAIMS_WITHOUT_EVIDENCE and not kept:
            dropped_claims.append(text)
            continue

        # ③ 숫자가 근거에 없으면 버린다.
        if CHECK_PROPER_NOUNS and kept and not _facts_grounded(text, kept, state):
            dropped_claims.append(text)
            continue

        claims.append({"text": text.strip(), "evidence": kept})

    return {
        "claims": claims,
        "dropped_evidence": dropped_evidence,
        "dropped_claims": dropped_claims,
    }


# ── ④ assemble ─────────────────────────────────────────────────


def assemble(state: SummaryState) -> dict:
    """
    살아남은 claims 로 본문을 잇고, 반영된 일지를 센다.

    본문을 모델에게 따로 받지 않는 이유가 여기 있다. 받으면 claims 밖 문장이
    생기고, 그 문장은 위 세 단계를 거치지 않은 채 교사에게 간다.
    """
    claims = state.get("claims") or []

    content = " ".join(c["text"] for c in claims) if CONTENT_FROM_CLAIMS_ONLY else ""

    if COVERAGE_FROM_EVIDENCE:
        covered = {e["journal_entry_id"] for c in claims for e in c["evidence"]}
    else:
        covered = set()

    # 익명화가 실패했는지 본다. 이름을 지우는 일은 프롬프트가 하고(ANONYMIZE_OTHER_NAMES),
    # 여기서는 **남았는지만 센다.** 코드가 자동으로 고치면 프롬프트가 안 지켜졌다는
    # 사실이 가려지고, 조사가 깨져 문장이 어색해진다 ("기관 내 아동가").
    #
    # ⚠️ 받은 목록은 ACTIVE 명부 아이만 담긴다. 형제·교사·동의 전 아동은 빠지므로
    # 여기서 0 이 나와도 "안 샌다" 는 뜻이 아니다 (CRITERIA §3).
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
