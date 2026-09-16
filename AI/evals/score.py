"""
run_eval.py 가 만든 결과 파일을 채점한다.

    python evals/score.py <결과.json>

정답 필드는 전부 선택이다. 없는 항목은 그 지표를 건너뛴다 —
데이터가 늘어날 때마다 스크립트를 고치지 않기 위해서다.

    expected_child_id            아동 ID. null 이면 "명부에 없는 아이"(정답 = unmatched)
    expected_status              auto / review / multi / unmatched
    expected_multi_reason        co_mention / ambiguous_identity / null(대등 언급 아님)
    expected_hint_mismatch       표지를 뒤집는 것이 맞는 케이스인지
    expected_mentioned_child_ids 본문에 이름이 등장해야 하는 아이들

가장 중요한 숫자는 맨 위의 **오매칭**이다. auto 는 사람 확인 없이 지나가므로,
여기서 틀리면 다른 아이의 기록이 부모에게 간다. 나머지 지표가 좋아도
이 값이 0 이 아니면 τ 를 올려야 한다.
"""

import argparse
import collections
import json
from pathlib import Path

BANDS = [(0.0, 0.6), (0.6, 0.7), (0.7, 0.8), (0.8, 0.9), (0.9, 0.95), (0.95, 1.01)]
SWEEP = (0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 0.98)


def pct(part: int, whole: int) -> str:
    return f"{part / whole * 100:.1f}%" if whole else "-"


def first_choice_ok(row: dict) -> bool | None:
    """1순위 판정이 정답인가. 정답 라벨이 없으면 None."""
    if "expected_child_id" not in row:
        return None
    expected = row["expected_child_id"]
    if expected is None:
        return row["status"] == "unmatched"
    return row["matched_child_id"] == expected


def answer_reachable(row: dict) -> bool | None:
    """
    교사에게 정답이 도달하는가.

    multi 는 matched_child_id 가 비어 있지만 후보로 정답을 보여주므로,
    교사 입장에서는 한 번 고르면 끝이다. 1순위 정답률과 따로 잰다.
    """
    if "expected_child_id" not in row:
        return None
    expected = row["expected_child_id"]
    if expected is None:
        return row["status"] == "unmatched"
    if row["matched_child_id"] == expected:
        return True
    return any(c["child_id"] == expected for c in row.get("candidates", []))


def report(rows: list[dict]) -> None:
    errors = [r for r in rows if r["status"] == "ERROR"]
    ok = [r for r in rows if r["status"] != "ERROR"]
    labeled = [r for r in ok if r.get("expected_child_id") is not None]
    unlisted = [
        r for r in ok
        if "expected_child_id" in r and r["expected_child_id"] is None
    ]

    print(f"■ 전체 {len(rows)}건 / 오류 {len(errors)}건")
    print(f"   정답 라벨 {len(labeled)}건 · 명부에 없는 아이 {len(unlisted)}건\n")

    counts = collections.Counter(r["status"] for r in ok)
    print("■ status 분포")
    for status in ("auto", "review", "multi", "unmatched"):
        print(f"   {status:10} {counts[status]:5}건  {pct(counts[status], len(ok))}")

    # ── 가장 중요한 지표 ──────────────────────────────────
    auto = [r for r in ok if r["status"] == "auto"]
    auto_wrong = [r for r in auto if first_choice_ok(r) is False]
    print(f"\n■ 오매칭 {len(auto_wrong)} / auto {len(auto)}  ({pct(len(auto_wrong), len(auto))})")
    print("   auto 는 사람 확인을 건너뛴다. 이 값이 0 이 아니면 τ 를 올려야 한다.")
    for r in auto_wrong[:5]:
        print(f"     X {r['case_id']} exp={r['expected_child_id']} got={r['matched_child_id']} "
              f"conf={r['confidence']:.2f} | {r['content'][:44]}")

    queue = [r for r in ok if r["status"] in ("review", "multi")]
    reach = [r for r in queue if answer_reachable(r)]
    print(f"\n■ 확인 큐 {len(queue)}건 중 정답이 후보에 포함 {len(reach)}  ({pct(len(reach), len(queue))})")

    miss = [r for r in labeled if r["status"] == "unmatched"]
    false_pos = [r for r in unlisted if r["status"] != "unmatched"]
    print(f"■ 놓침(명부에 있는데 unmatched) {len(miss)}건")
    print(f"■ 오탐(명부에 없는데 특정)      {len(false_pos)} / {len(unlisted)}")

    scored = [r for r in ok if first_choice_ok(r) is not None]
    if scored:
        one = sum(1 for r in scored if first_choice_ok(r))
        both = sum(1 for r in scored if answer_reachable(r))
        print(f"\n■ 1순위 정답률          {one}/{len(scored)}  {pct(one, len(scored))}")
        print(f"■ 정답 도달률(multi 포함) {both}/{len(scored)}  {pct(both, len(scored))}")

    # ── 선택 정답 필드 ────────────────────────────────────
    st = [r for r in ok if r.get("expected_status")]
    if st:
        hit = [r for r in st if r["status"] == r["expected_status"]]
        print(f"\n■ status 정확도 {len(hit)}/{len(st)}  {pct(len(hit), len(st))}")
        for r in [x for x in st if x["status"] != x["expected_status"]][:5]:
            print(f"     X {r['case_id']} 기대={r['expected_status']} 실제={r['status']} "
                  f"| {r['content'][:44]}")

    hm = [r for r in ok if r.get("expected_hint_mismatch") is not None]
    if hm:
        hit = [r for r in hm if r["hint_mismatch"] == r["expected_hint_mismatch"]]
        print(f"\n■ hint_mismatch 정확도 {len(hit)}/{len(hm)}  {pct(len(hit), len(hm))}")
        print("   표지가 틀린 기록을 본문으로 바로잡는지. 이게 매칭 에이전트의 존재 이유다.")
        for r in [x for x in hm if x["hint_mismatch"] != x["expected_hint_mismatch"]][:5]:
            print(f"     X {r['case_id']} 기대={r['expected_hint_mismatch']} "
                  f"실제={r['hint_mismatch']} | {r['content'][:44]}")

    mr = [r for r in ok if r.get("expected_multi_reason", "__skip__") != "__skip__"]
    if mr:
        hit = [r for r in mr if r["multi_reason"] == r["expected_multi_reason"]]
        print(f"\n■ multi_reason 정확도 {len(hit)}/{len(mr)}  {pct(len(hit), len(mr))}")
        print("   두 아이가 대등하게 나온 기록인지, 한 명은 스쳐 지나간 것인지.")
        print("   코드는 구별 못 한다 — 본문을 읽어야 알 수 있어 LLM 판단력을 잰다.")
        for r in [x for x in mr if x["multi_reason"] != x["expected_multi_reason"]][:5]:
            print(f"     X {r['case_id']} 기대={r['expected_multi_reason']} "
                  f"실제={r['multi_reason']} | {r['content'][:44]}")

    mc = [r for r in ok if r.get("expected_mentioned_child_ids") is not None]
    if mc:
        hit = [r for r in mc
               if set(r["mentioned_child_ids"]) == set(r["expected_mentioned_child_ids"])]
        print(f"\n■ mentioned_child_ids 정확도 {len(hit)}/{len(mc)}  {pct(len(hit), len(mc))}")
        print("   본문에 이름이 남은 다른 아이를 빠짐없이 넘기는지.")
        print("   여기가 비면 Validation 이 개인정보 노출을 못 잡는다.")
        for r in [x for x in mc
                  if set(x["mentioned_child_ids"]) != set(x["expected_mentioned_child_ids"])][:5]:
            print(f"     X {r['case_id']} 기대={r['expected_mentioned_child_ids']} "
                  f"실제={r['mentioned_child_ids']}")

    conf = [r for r in ok if r.get("confusion_child_id") is not None]
    if conf:
        c_auto = [r for r in conf if r["status"] == "auto"]
        c_wrong = [r for r in c_auto if first_choice_ok(r) is False]
        c_ok = sum(1 for r in conf if first_choice_ok(r))
        print(f"\n■ 헷갈림 유도 {len(conf)}건 | auto {len(c_auto)} 중 오매칭 {len(c_wrong)} "
              f"| 1순위 정답 {pct(c_ok, len(conf))}")

    called = sum(1 for r in ok if r["llm_called"])
    print(f"\n■ LLM 호출 {called}/{len(ok)}  ({pct(called, len(ok))})")

    print("\n■ confidence 분포 (구간별 오답)")
    for lo, hi in BANDS:
        band = [r for r in ok if lo <= r["confidence"] < hi]
        bad = [r for r in band if first_choice_ok(r) is False]
        print(f"   {lo:.2f}~{hi:.2f}  {len(band):5}건   오답 {len(bad)}")

    print("\n■ τ_auto 스윕 (auto 게이트만 바꿨을 때)")
    print("   τ      자동 확정        오매칭")
    for tau in SWEEP:
        would = [r for r in ok
                 if r["status"] in ("auto", "review")
                 and r["confidence"] >= tau
                 and not r["hint_mismatch"]]
        bad = [r for r in would if first_choice_ok(r) is False]
        print(f"   {tau:.2f}  {len(would):5}건 {pct(len(would), len(ok)):>7}   "
              f"{len(bad):4}건 {pct(len(bad), len(would)) if would else '-':>7}")

    if errors:
        print("\n■ 오류")
        for r in errors[:5]:
            print(f"   {r['case_id']} {r['error'][:110]}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("result", help="run_eval.py 가 만든 결과 JSON")
    args = ap.parse_args()
    report(json.loads(Path(args.result).read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
