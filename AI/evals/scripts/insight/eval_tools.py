# AI/evals/scripts/insight/eval_tools.py
"""
도구 사용(딥다이브 3) 평가. 보강 단계(nodes._lookup)만 실제 Luna 로 돌린다.

    python evals/scripts/insight/eval_tools.py --runs 3

실데이터 4~6월에는 한 기관으로 걸린 상황(화장실·차량)의 다른 기관 기록이 아예 없다.
그래서 "있으면 찾아 살리는지" 는 실데이터로 잴 수 없어, 가짜 아이·가짜 문장으로 10건을 만들었다.
처음 인사이트(한 기관, 대처 없음)는 정해 두고, 지난 기록만 바꾼다.

  찾아야 함 (5)   다른 기관에 같은 상황 + 대처와 결과가 있다 → 살리고, 그 기록을 근거로
  찾으면 안 됨 (5) 관계없는 기록 · 같은 기관 기록 · 낱말만 겹치는 다른 상황 · 동의 안 한 기관 · 없음 → 버림

결과는 evals/generated/insight/runs/tools_<name>.json (git-ignored).
"""

import argparse
import json
import sys
import time
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from dotenv import load_dotenv  # noqa: E402

load_dotenv(AI_DIR / ".env")

from insight import nodes  # noqa: E402
from insight.nodes import perceive  # noqa: E402

TYPES = {1: "학교", 2: "센터", 3: "활동지원사"}
CONSENT = [{"institution_id": 1, "institution_type": "학교"}, {"institution_id": 2, "institution_type": "센터"}]
_jid = iter(range(9000, 99999))


def c(cid, inst, date, text):
    return {"claim_id": cid, "text": text, "entry_date": date, "institution_id": inst,
            "institution_type": TYPES[inst], "evidence": [{"journal_entry_id": next(_jid), "quote": text}]}


def single(situation, behavior, content, ids):
    return {"situation": situation, "behavior": behavior, "supports": [], "claim_ids": ids, "content": content}


#: 이번 기간(10/19~10/25) 센터 기록 둘 + 처음 인사이트. 상황마다 하나씩.
NOW = {
    "차량": ([c("n1-0", 2, "2026-10-20", "하원 차량 앞에서 바닥에 앉아 타지 않으려 했다."),
              c("n1-1", 2, "2026-10-23", "차량 탑승을 거부하고 바닥에 앉아 울었다.")],
             single("하원 차량 탑승 전", "바닥에 앉아 타지 않으려 했다",
                    "센터에서 하원 차량 앞에서 바닥에 앉아 타지 않으려 한 일이 반복됐다.", ["n1-0", "n1-1"])),
    "화장실": ([c("n2-0", 2, "2026-10-20", "화장실에 가자고 하자 고개를 저으며 거부했다."),
                c("n2-1", 2, "2026-10-22", "화장실 가기를 거부하고 교실 구석으로 갔다.")],
               single("화장실에 가자고 할 때", "거부했다",
                      "센터에서 화장실에 가자고 하면 거부하는 일이 반복됐다.", ["n2-0", "n2-1"])),
    "소리": ([c("n3-0", 2, "2026-10-21", "음악 시간 큰 소리에 귀를 막고 교실 밖으로 나가려 했다."),
              c("n3-1", 2, "2026-10-24", "공연 연습 소리가 커지자 귀를 막고 주저앉았다.")],
             single("큰 소리가 날 때", "귀를 막고 나가려 하거나 주저앉았다",
                    "센터에서 큰 소리가 나면 귀를 막고 자리를 피하려 했다.", ["n3-0", "n3-1"])),
    "손씻기": ([c("n4-0", 2, "2026-10-20", "간식 전 손 씻기를 거부하고 소리를 질렀다."),
                c("n4-1", 2, "2026-10-23", "손 씻기를 하자고 하자 손을 뒤로 숨기고 거부했다.")],
               single("간식 전 손 씻기", "거부했다", "센터에서 간식 전 손 씻기를 거부하는 일이 반복됐다.",
                      ["n4-0", "n4-1"])),
    "낮잠": ([c("n5-0", 2, "2026-10-21", "낮잠 시간에 누우라고 하자 울며 일어나 돌아다녔다."),
              c("n5-1", 2, "2026-10-22", "낮잠 시간 내내 울며 눕지 않으려 했다.")],
             single("낮잠 시간", "울며 눕지 않으려 했다", "센터에서 낮잠 시간에 울며 눕지 않으려 한 일이 반복됐다.",
                    ["n5-0", "n5-1"])),
}

#: 다른 기관(학교)에 같은 상황 + 대처와 결과
MATCH = {
    "차량": c("h1-0", 1, "2026-10-08", "하교 차량 앞에서 앉아 버렸고, 같은 차를 타는 친구가 손을 흔들자 일어나 탔다."),
    "화장실": c("h2-0", 1, "2026-10-07", "화장실 가기를 거부했으나 체크표에 스티커를 붙이자고 하자 스스로 다녀왔다."),
    "소리": c("h3-0", 1, "2026-10-09", "음악 시간 큰 소리에 귀를 막았고, 귀덮개를 씌워 주자 자리로 돌아와 참여했다."),
    "손씻기": c("h4-0", 1, "2026-10-06", "손 씻기를 거부했으나 거품 비누를 직접 짜게 하자 끝까지 씻었다."),
    "낮잠": c("h5-0", 1, "2026-10-10", "쉬는 시간에 누우라고 하자 울었고, 좋아하는 담요를 덮어 주자 조용히 누웠다."),
}

UNRELATED = [c("u1-0", 1, "2026-10-08", "급식 시간에 반찬을 골고루 먹었다."),
             c("u1-1", 1, "2026-10-09", "미술 시간에 색종이를 반으로 접어 하트를 오렸다.")]

CASES = []
for key, (now, raw) in NOW.items():
    CASES.append({"id": f"찾음_{key}", "group": "찾아야 함", "now": now, "raw": raw,
                  "history": [MATCH[key]] + UNRELATED, "expect": MATCH[key]["claim_id"]})
CASES += [
    {"id": "안찾음_관계없음", "group": "찾으면 안 됨", "now": NOW["차량"][0], "raw": NOW["차량"][1],
     "history": UNRELATED, "expect": None},
    {"id": "안찾음_같은기관", "group": "찾으면 안 됨", "now": NOW["화장실"][0], "raw": NOW["화장실"][1],
     "history": [c("s1-0", 2, "2026-10-08", "화장실 가기를 거부해 손을 잡고 함께 갔다.")] + UNRELATED, "expect": None},
    {"id": "안찾음_낱말만겹침", "group": "찾으면 안 됨", "now": NOW["차량"][0], "raw": NOW["차량"][1],
     "history": [c("w1-0", 1, "2026-10-08", "자유 놀이 시간에 장난감 차량을 바닥에 앉아 굴리며 놀았다.")] + UNRELATED,
     "expect": None},
    {"id": "안찾음_동의안함", "group": "찾으면 안 됨", "now": NOW["소리"][0], "raw": NOW["소리"][1],
     "history": [c("x1-0", 3, "2026-10-09", "큰 소리에 귀를 막았고, 귀덮개를 씌워 주자 진정했다.")] + UNRELATED,
     "expect": None},
    {"id": "안찾음_지난기록없음", "group": "찾으면 안 됨", "now": NOW["낮잠"][0], "raw": NOW["낮잠"][1],
     "history": [], "expect": None},
]


def run_case(case):
    state = perceive({"child_name": "아동 A", "period_from": "2026-10-19", "period_to": "2026-10-25",
                      "claims": [dict(x) for x in case["now"]], "history_claims": [dict(x) for x in case["history"]],
                      "consented_institutions": CONSENT})
    if not state["history_index"]:
        return {"rescued": False, "why": "지난 기록 없음(도구 안 부름)", "tool_calls": 0, "looked_up": [], "after": None}
    item = {"pos": 0, "raw": case["raw"], "action": "lookup",
            "reasons": [{"kind": "한기관_대처없음", "field": "", "sentence": ""}]}
    ins, entry = nodes._lookup(state, item, nodes._known_types(state))
    return {"rescued": ins is not None, "why": entry["why"], "tool_calls": entry["tool_calls"],
            "looked_up": entry["looked_up"], "after": entry["after"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--name", default="latest")
    args = ap.parse_args()

    records, t0 = [], time.time()
    for n in range(1, args.runs + 1):
        for case in CASES:
            r = run_case(case)
            if case["expect"]:
                ok = r["rescued"] and case["expect"] in (r["looked_up"] or [])
            else:
                ok = not r["rescued"]
            records.append({"run": n, "id": case["id"], "group": case["group"], "ok": ok, **r})
            print(f"[{n}회] {'OK ' if ok else 'XX '}{case['id']:<14} 살림={r['rescued']} 찾은것={r['looked_up']} "
                  f"도구={r['tool_calls']} {r['why']}")
            if r["after"]:
                print(f"        → {r['after']}")

    print("\n===== 도구 평가 =====")
    for g in ("찾아야 함", "찾으면 안 됨"):
        rs = [r for r in records if r["group"] == g]
        print(f"  {g:<8} {sum(r['ok'] for r in rs)}/{len(rs)}")
    wrong = [r for r in records if r["group"] == "찾으면 안 됨" and r["rescued"]]
    print(f"  잘못 묶음 {len(wrong)}건 {[r['id'] for r in wrong]}")
    calls = [r["tool_calls"] for r in records if r["tool_calls"]]
    print(f"  도구 호출 평균 {sum(calls) / max(len(calls), 1):.1f}번 · {time.time() - t0:.0f}초")
    out = AI_DIR / "evals" / "generated" / "insight" / "runs" / f"tools_{args.name}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  저장: {out}")
    sys.exit(0 if not wrong else 1)


if __name__ == "__main__":
    main()
