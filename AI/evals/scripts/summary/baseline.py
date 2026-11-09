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

SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def _claims_copy(sources) -> list[dict]:
    return [
        {
            "text": s.content.strip(),
            "evidence": [{"journal_entry_id": s.journal_entry_id, "quote": s.content.strip()}],
        }
        for s in sources
        if s.content and s.content.strip()
    ]


def _claims_first_sentence(sources) -> list[dict]:
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
