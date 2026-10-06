"""
run_eval.py / baseline.py 가 만든 결과 파일을 채점한다. LLM 을 부르지 않는다.

    python evals/scripts/summary/score.py <결과.json> [--sample 10] [--seed 1]

**점수 하나로 요약을 판단할 수 없다.** 환각·누락 지표는 일지를 그대로 베끼기만 해도
만점이 나온다 (baseline.py --mode copy 로 확인할 수 있다). 그래서 네 가지를 같이 본다.

    버림      모델이 원문에 없는 인용을 냈는가       코드가 센다
    누락      반영되지 않은 일지가 있는가            코드가 센다
    인용 길이  근거가 좁은 구간인가                   코드가 센다
    묶음      여러 일지를 한 문장으로 이었는가        코드가 센다
    뒷받침    문장이 정말 그 인용으로 설명되는가      **사람이 본다**

마지막 하나가 핵심이다. 앞의 넷이 전부 통과해도 문장이 인용을 넘어설 수 있다.
--sample 로 사람이 볼 표본을 뽑는다.
"""

import argparse
import json
import random
import statistics
import sys
from pathlib import Path


def _median(values):
    return round(statistics.median(values), 3) if values else None


def _pct(part, whole):
    return f"{part / whole * 100:.1f}%" if whole else "—"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("result", help="run_eval.py 또는 baseline.py 가 만든 결과 JSON")
    ap.add_argument("--sample", type=int, default=0, help="사람이 볼 표본 N 건을 뽑는다")
    ap.add_argument("--seed", type=int, default=1, help="표본 추출 시드. 고정해야 재현된다")
    args = ap.parse_args()

    records = json.loads(Path(args.result).read_text(encoding="utf-8"))["records"]
    total = len(records)
    failed = [r for r in records if r.get("error")]
    ok = [r for r in records if not r.get("error")]

    print(f"■ 전체 {total}건 / 실행 오류 {len(failed)}건")
    for r in failed[:5]:
        print(f"     X {r.get('case_id')} {r['error'][:80]}")

    # ── 1. 버림 — 모델이 원문에 없는 인용을 낸 횟수 ──
    dropped_ev = sum(r["diag"].get("dropped_evidence", 0) for r in ok)
    dropped_cl = sum(len(r["diag"].get("dropped_claims", [])) for r in ok)
    kept_ev = sum(len(e) for r in ok for e in (c["evidence"] for c in r["claims"]))
    print(f"\n■ 버려진 근거 {dropped_ev} / 모델이 낸 근거 {dropped_ev + kept_ev}")
    print(f"■ 버려진 문장 {dropped_cl}건")
    print("   인용을 다듬거나 숫자를 바꾼 횟수다. 0 이 좋지만, 0 이라고 요약이 좋은 것은 아니다.")

    # ── 2. 누락 ──
    with_uncovered = [r for r in ok if r["uncovered_entry_ids"]]
    unexpected = []
    for r in with_uncovered:
        droppable = set(r.get("expected_droppable_entry_ids") or [])
        left = [i for i in r["uncovered_entry_ids"] if i not in droppable]
        if left:
            unexpected.append((r.get("case_id"), left))
    print(f"\n■ 반영 안 된 일지가 있는 건 {len(with_uncovered)} / {len(ok)}")
    print(f"■ 그중 빠지면 안 되는 일지가 빠진 건 {len(unexpected)}건")
    for case_id, left in unexpected[:5]:
        print(f"     X {case_id} uncovered={left}")

    # 정답 라벨이 있으면 covered 를 대조한다
    labeled = [r for r in ok if r.get("expected_covered_entry_ids")]
    if labeled:
        hit = sum(
            1 for r in labeled
            if set(r["expected_covered_entry_ids"]) <= set(r["covered_entry_ids"])
        )
        print(f"■ 반영돼야 할 일지를 전부 반영한 건 {hit} / {len(labeled)}  ({_pct(hit, len(labeled))})")

    # ── 3. 인용 길이 ──
    ratios = [
        e["quote_ratio"]
        for r in ok for c in r["claims"] for e in c["evidence"]
        if e.get("quote_ratio") is not None
    ]
    whole = sum(1 for x in ratios if x >= 0.999)
    print(f"\n■ 인용 길이 ÷ 원문 길이  중앙값 {_median(ratios)}  (근거 {len(ratios)}개)")
    print(f"■ 일지를 통째로 인용한 근거 {whole} / {len(ratios)}  ({_pct(whole, len(ratios))})")
    print("   1.0 이면 Gate 1 에서 하이라이트가 일지 전체라 아무것도 알려주지 못한다.")

    # ── 4. 묶음 — 베끼기와 요약을 가르는 자리 ──
    claims = [c for r in ok for c in r["claims"]]
    multi = sum(1 for c in claims if c["multi_entry"])
    multi_src = [r for r in ok if r.get("n_sources", 0) >= 2]
    with_multi = sum(1 for r in multi_src if any(c["multi_entry"] for c in r["claims"]))
    print(f"\n■ 여러 일지에 걸친 문장 {multi} / {len(claims)}  ({_pct(multi, len(claims))})")
    print(f"■ 일지가 2건 이상인 날 중 묶은 문장이 하나라도 있는 건 "
          f"{with_multi} / {len(multi_src)}  ({_pct(with_multi, len(multi_src))})")
    print("   **여기가 베끼기와 요약을 가른다.** 일지를 그대로 옮기면 이 값이 0 이다.")

    # ── 5. 나오면 안 되는 문자열 ──
    leaks = []
    for r in ok:
        for bad in r.get("expected_absent") or []:
            if bad and bad in r["content"]:
                leaks.append((r.get("case_id"), bad))
    print(f"\n■ 나오면 안 되는 문자열이 나온 건 {len(leaks)}건")
    for case_id, bad in leaks[:5]:
        print(f"     X {case_id} \"{bad}\"")

    # ── 6. 호출과 토큰 ──
    called = sum(1 for r in ok if r.get("llm_called"))
    cached = [
        r["diag"]["usage"].get("prompt_tokens_details", {}).get("cached_tokens", 0)
        for r in ok if r["diag"].get("usage")
    ]
    print(f"\n■ LLM 호출 {called} / {len(ok)}  ({_pct(called, len(ok))})")
    if any(cached):
        print(f"■ 캐시된 프롬프트 토큰 중앙값 {_median(cached)}")

    # ── 7. 사람이 볼 표본 ──
    if args.sample and claims:
        random.seed(args.seed)
        picked = random.sample(claims, min(args.sample, len(claims)))
        print(f"\n■ 뒷받침 표본 {len(picked)}건 (seed={args.seed})")
        print("   문장이 **정말 그 인용으로 설명되는지** 사람이 본다. 코드는 못 잡는다.")
        for i, c in enumerate(picked, 1):
            srcs = ", ".join(str(e["journal_entry_id"]) for e in c["evidence"])
            print(f"\n   [{i}] {c['text']}")
            print(f"       근거 일지 {srcs} · 인용 비율 "
                  f"{[e['quote_ratio'] for e in c['evidence']]}")

    print("\n" + "─" * 60)
    print("0 건은 분모 없이 적지 않는다. 보고할 때 전체 건수를 함께 쓴다.")
    print("규칙 기준선(baseline.py)과 나란히 채점하지 않은 숫자는 혼자서는 뜻이 없다.")

    if failed:
        print(f"\n실행 오류 {len(failed)}건이라 실패로 끝낸다.")
        sys.exit(1)


if __name__ == "__main__":
    main()
