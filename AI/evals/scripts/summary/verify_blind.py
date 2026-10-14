# AI/evals/scripts/summary/verify_blind.py
"""
블라인드 세트를 **에이전트에 부르기 전에** 검사한다.

    python evals/scripts/summary/verify_blind.py <세트.json>

여기서 걸리면 에이전트가 아니라 문제가 틀린 것이다. 돌린 뒤에 알면 그 세트는
소모되므로, 반드시 먼저 돌린다 (matching/HOLDOUT.md 와 같은 절차).
"""

import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(ROOT))

from summary.schemas import SummaryInput  # noqa: E402

PII = re.compile(r"\d{2,3}-\d{3,4}-\d{4}|자폐|지적장애|뇌병변|ADHD|진단명|소견서")


def main(path: str) -> None:
    cases = json.loads(Path(path).read_text(encoding="utf-8"))
    bad: list[str] = []

    def ck(cond, msg):
        if not cond:
            bad.append(msg)

    all_entry_ids: list[int] = []
    for c in cases:
        cid = c["case_id"]
        SummaryInput(**{k: v for k, v in c.items() if k in SummaryInput.model_fields})

        src_ids = [s["journal_entry_id"] for s in c["sources"]]
        all_entry_ids += src_ids
        ck(len(src_ids) == len(set(src_ids)), f"{cid} 일지 id 중복")
        ck(bool(c["sources"]), f"{cid} 일지 없음")

        cov = set(c["expected_covered_entry_ids"])
        drop = set(c["expected_droppable_entry_ids"])
        ck(cov | drop == set(src_ids), f"{cid} covered+droppable 이 일지 전체와 다름")
        ck(not (cov & drop), f"{cid} covered 와 droppable 이 겹침")

        for s in c["sources"]:
            ck(s.get("entry_date") == c["entry_date"], f"{cid} 일지 날짜가 묶음 날짜와 다름")
            ck(not PII.search(s["content"]), f"{cid} 진단명·연락처 의심")

        text = " ".join(s["content"] for s in c["sources"])

        # 명부 안 이름은 본문에 실제로 있어야 한다 — 없으면 검사가 헛돈다
        for n in c["other_child_names"]:
            ck(n in text, f"{cid} other_child_names '{n}' 가 본문에 없음")

        # names 갈래의 금지어는 본문에 있는 실제 이름이어야 한다
        if c["category"] == "names":
            ck(c["expected_absent"], f"{cid} names 인데 expected_absent 비어 있음")
            full = [t for t in c["expected_absent"] if len(t) >= 3]
            ck(any(t in text for t in full), f"{cid} 금지 이름이 본문에 하나도 없음")

        if c["category"] == "empty":
            ck(c.get("expected_status") == 503, f"{cid} empty 인데 expected_status 가 503 아님")
            ck(not cov, f"{cid} empty 인데 covered 가 있음")
            ck(not c["expected_facts"], f"{cid} empty 인데 expected_facts 가 있음")
        else:
            ck(c["expected_facts"], f"{cid} 담겨야 할 사실이 없음")
            ck(c.get("expected_status") in (None, 200), f"{cid} 예상 상태가 200 이 아님")

    ck(len(all_entry_ids) == len(set(all_entry_ids)), "세트 전체에서 일지 id 중복")
    ids = [c["case_id"] for c in cases]
    ck(len(ids) == len(set(ids)), "case_id 중복")
    kids = [c["child_id"] for c in cases]
    ck(len(kids) == len(set(kids)), "child_id 중복")

    inst = {}
    for c in cases:
        inst.setdefault(c["institution_id"], set()).add(c["institution_name"])
    for k, names in inst.items():
        ck(len(names) == 1, f"institution_id {k} 에 이름이 둘 이상: {names}")

    print(f"케이스 {len(cases)}건 · 일지 {len(all_entry_ids)}건 "
          f"({min(all_entry_ids)}~{max(all_entry_ids)})")
    print("갈래", dict(Counter(c["category"] for c in cases)))
    print("기관", dict(Counter(c["institution_name"] for c in cases)))
    if bad:
        print(f"\n실패 {len(bad)}건")
        for b in bad:
            print("  ✗", b)
        sys.exit(1)
    print("\n이상 없음 ✅")


if __name__ == "__main__":
    main(sys.argv[1])
