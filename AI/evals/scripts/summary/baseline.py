"""
LLM 없이 규칙만으로 요약을 만든다. 에이전트 점수와 나란히 채점하기 위한 기준선이다.

    python evals/scripts/summary/baseline.py <입력.json> <결과.json> --mode copy
    python evals/scripts/summary/score.py <결과.json>

매칭의 baseline.py 와 같은 이유로 있다. 2026-09-16 에 매칭 자체 채점 96% 가 나왔을 때
표지만 읽는 세 줄짜리 코드가 100% 를 받았다. 점수 하나만 보면 잘한 건지 알 수 없다.

**요약에서는 이게 더 심하다.** 일지를 그대로 베끼면
  버려진 근거 0 · 누락 0 · 지어낸 문장 0
이 전부 만점으로 나온다. 인용이 원문 그 자체이므로 대조를 통과할 수밖에 없다.

그래서 환각·누락 지표만으로 "요약이 좋다" 고 말할 수 없다. 베끼기가 못 하는 것은
둘이다 — **여러 일지를 한 문장으로 잇는 것**과 **좁은 구간을 인용하는 것**.
score.py 가 그 둘을 따로 찍는 이유다.

출력은 run_eval.py 와 같은 모양이라 score.py 가 둘 다 채점한다.
LLM 을 부르지 않으므로 즉시 끝나고 결과가 매번 같다.
"""

import argparse
import json
import re
import sys
from pathlib import Path

AI_DIR = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(AI_DIR))

from summary.nodes import assemble, gather, ground  # noqa: E402
from summary.schemas import SummaryInput  # noqa: E402

from run_eval import EXPECTED, PASSTHROUGH, _claim_record  # noqa: E402

#: 문장 끝. 한국어 관찰일지는 "~함." "~했다." 로 끝나는 경우가 대부분이다.
SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def _claims_copy(sources) -> list[dict]:
    """일지 하나를 문장 하나로 통째로 옮긴다. 가장 게으른 요약."""
    return [
        {
            "text": s.content.strip(),
            "evidence": [{"journal_entry_id": s.journal_entry_id, "quote": s.content.strip()}],
        }
        for s in sources
        if s.content and s.content.strip()
    ]


def _claims_first_sentence(sources) -> list[dict]:
    """일지마다 첫 문장만 가져온다. 길이는 줄지만 뒤쪽 내용이 통째로 누락된다."""
    claims = []
    for s in sources:
        head = SENTENCE_END.split((s.content or "").strip(), maxsplit=1)[0].strip()
        if head:
            claims.append(
                {
                    "text": head,
                    "evidence": [{"journal_entry_id": s.journal_entry_id, "quote": head}],
                }
            )
    return claims


MODES = {"copy": _claims_copy, "first-sentence": _claims_first_sentence}


def run_one(case: dict, mode: str) -> dict:
    record = {k: case.get(k) for k in PASSTHROUGH}
    record.update({k: case.get(k) for k in EXPECTED})
    record["n_sources"] = len(case.get("sources") or [])

    payload = SummaryInput(**{k: v for k, v in case.items()
                              if k in SummaryInput.model_fields})
    state = {
        "child_id": payload.child_id,
        "child_name": payload.child_name,
        "entry_date": payload.entry_date,
        "institution_id": payload.institution_id,
        "institution_name": payload.institution_name,
        "sources": payload.sources,
    }
    # 에이전트와 **같은 거르기 코드**를 통과시킨다. 기준선에만 느슨한 규칙을
    # 쓰면 비교가 성립하지 않는다.
    state.update(gather(state))
    state["raw_claims"] = MODES[mode](payload.sources)
    state.update(ground(state))
    state.update(assemble(state))

    record.update(
        content=state["content"],
        claims=[_claim_record(c, state["by_id"]) for c in state["claims"]],
        covered_entry_ids=state["covered_entry_ids"],
        uncovered_entry_ids=state["uncovered_entry_ids"],
        llm_called=False,
        error=None,
        diag={
            "dropped_evidence": state.get("dropped_evidence", 0),
            "dropped_claims": state.get("dropped_claims", []),
            "source_chars": sum(len(v) for v in state["by_id"].values()),
            "content_chars": len(state["content"]),
            "usage": {},
        },
    )
    return record


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--mode", choices=sorted(MODES), default="copy")
    args = ap.parse_args()

    cases = json.loads(Path(args.src).read_text(encoding="utf-8"))
    records = [run_one(c, args.mode) for c in cases]

    out = Path(args.dst)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps({"records": records}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"기준선({args.mode}) {len(records)}건 → {out}")


if __name__ == "__main__":
    main()
