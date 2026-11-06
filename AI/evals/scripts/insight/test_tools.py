# AI/evals/scripts/insight/test_tools.py
"""
도구 사용(딥다이브 3) 단위 테스트. LLM 호출 없음 — ask_with_tools 를 가짜로 바꿔 끼운다.

    python evals/scripts/insight/test_tools.py

확인하는 것
  - find_claims 는 history_claims 안에서만, 기간 앞쪽만, 인용 없이 찾는다
  - 동의 안 한 기관의 지난 기록은 perceive 에서 빠져 도구에도 안 나온다
  - 보강은 처음 근거 + 도구가 실제로 돌려준 claim 만 근거로 쓸 수 있다
  - 보강한 것도 성립 조건·약한 검사를 다시 통과해야 하고, 실패하면 버린다
  - INSIGHT_TOOLS 를 끄면 예전처럼 버린다 (롤백)
"""

import sys
from pathlib import Path
from types import SimpleNamespace

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from insight import config, nodes  # noqa: E402
from insight.llm import LlmError  # noqa: E402
from insight.nodes import perceive, reflect, refine  # noqa: E402
from insight.tools import find_claims  # noqa: E402

TYPES = {1: "학교", 2: "센터", 3: "활동지원사"}


def claim(cid, inst, jid, date, text):
    return {"claim_id": cid, "text": text, "entry_date": date, "institution_id": inst,
            "institution_type": TYPES[inst], "evidence": [{"journal_entry_id": jid, "quote": "인용 원문"}]}


# 이번 기간: 센터 기록 둘 (같은 상황, 대처 없음)
NOW = [claim("20-0", 2, 201, "2026-10-20", "차량 탑승 전에 바닥에 앉아 타지 않으려 했다."),
       claim("21-0", 2, 202, "2026-10-22", "차량 탑승을 거부하고 바닥에 앉았다.")]
# 지난 기록: 학교(동의), 활동지원사(동의 안 함), 관계없는 학교 기록
PAST = [claim("10-0", 1, 101, "2026-10-08", "하교 차량 앞에서 앉아 버렸고, 친구가 손을 흔들자 일어나 탔다."),
        claim("11-0", 3, 111, "2026-10-09", "차량 탑승을 거부해 손을 잡고 태웠다."),
        claim("12-0", 1, 112, "2026-10-10", "급식 시간에 반찬을 골고루 먹었다.")]

STATE = perceive({
    "child_name": "아동 A", "period_from": "2026-10-19", "period_to": "2026-10-25",
    "claims": [dict(c) for c in NOW], "history_claims": [dict(c) for c in PAST],
    "consented_institutions": [{"institution_id": 1, "institution_type": "학교"},
                               {"institution_id": 2, "institution_type": "센터"}],
})

SINGLE = {"situation": "차량 탑승 전", "behavior": "바닥에 앉아 타지 않으려 했다", "supports": [],
          "claim_ids": ["20-0", "21-0"], "content": "센터에서 차량 탑승 전에 바닥에 앉아 타지 않으려 한 일이 반복됐다."}
CROSS = {"situation": "차량 탑승 전", "behavior": "바닥에 앉아 타지 않으려 했다",
         "supports": [{"action": "학교에서 같은 차를 타는 친구가 손을 흔들었다.", "result": "일어나 탔다.",
                       "claim_ids": ["10-0"]}],
         "claim_ids": ["20-0", "21-0", "10-0"],
         "content": "센터와 학교에서 차량 탑승 전에 바닥에 앉는 일이 있었다. 학교에서는 친구가 손을 흔들자 일어나 탔다."}


def run(answer, tool_args=({"situation": "차량 탑승을 거부하고 바닥에 앉음", "weeks_back": 4},),
        tools_on=True, history=True):
    """reflect → refine. 가짜 Luna 는 tool_args 대로 도구를 부른 뒤 answer 를 답한다."""
    seen = []

    def fake(instructions, user, specs, run_tool, max_calls=2, timeout=None, deadline=None):
        if isinstance(answer, Exception):
            raise answer
        for args in tool_args[:max_calls]:
            seen.append(run_tool("find_claims", dict(args)))
        return SimpleNamespace(data=answer, usage={"tool_calls": min(len(tool_args), max_calls)})

    saved = (config.INSIGHT_WEAK_MODE, config.INSIGHT_TOOLS, nodes.ask_with_tools)
    config.INSIGHT_WEAK_MODE, config.INSIGHT_TOOLS, nodes.ask_with_tools = "refine", tools_on, fake
    state = STATE if history else {**STATE, "history_index": {}}
    try:
        out = refine(reflect({**state, "raw_insights": [SINGLE], "llm_error": None}))
    finally:
        config.INSIGHT_WEAK_MODE, config.INSIGHT_TOOLS, nodes.ask_with_tools = saved
    return out, seen


# ── find_claims ─────────────────────────────────────────────────

def test_동의_안_한_기관의_지난_기록은_도구에_안_나온다():
    assert set(STATE["history_index"]) == {"10-0", "12-0"}


def test_비슷한_상황을_찾고_인용은_돌려주지_않는다():
    found = find_claims(list(STATE["history_index"].values()), "차량 탑승을 거부하고 바닥에 앉음",
                        weeks_back=4, before="2026-10-19")
    assert [c["claim_id"] for c in found] == ["10-0"], found
    assert "evidence" not in found[0] and "quote" not in found[0]


def test_기간_밖_기록은_찾지_않는다():
    assert find_claims(list(STATE["history_index"].values()), "차량", weeks_back=1, before="2026-10-19") == []


# ── 보강 ────────────────────────────────────────────────────────

def test_찾은_기록으로_기관_간_인사이트가_되면_살린다():
    out, seen = run({"insights": [CROSS]})
    assert len(out["insights"]) == 1, out["weak_refined"]
    ins = out["insights"][0]
    assert ins.cross_institution and ins.looked_up_claim_ids == ["10-0"]
    assert out["weak_refined"][0]["outcome"] == "rescued" and seen[0][0]["claim_id"] == "10-0"


def test_도구가_돌려주지_않은_지난_기록은_근거로_못_쓴다():
    sneaky = {**CROSS, "claim_ids": ["20-0", "21-0", "12-0"], "supports": []}
    out, _ = run({"insights": [sneaky]})  # 12-0 은 history 에 있지만 도구가 돌려준 적 없음
    assert out["insights"] == [] and out["weak_refined"][0]["why"] == "조회 밖 근거"


def test_같은_상황이_없다고_하면_버린다():
    out, _ = run({"insights": []})
    assert out["insights"] == [] and out["weak_refined"][0]["why"] == "같은 상황 없음"


def test_보강해도_한_기관이면_버린다():
    out, _ = run({"insights": [SINGLE]})
    assert out["insights"] == [] and out["weak_refined"][0]["why"] == "여전히 약함"


def test_같은_기관_지난_기록을_대처로만_붙이면_버린다():
    # 11/07 도구 평가: 센터 인사이트에 센터의 지난 기록을 대처로 붙여 "대처 없음" 만 벗어남
    past_center = claim("13-0", 2, 113, "2026-10-09", "화장실 가기를 거부해 손을 잡고 함께 갔다.")
    state = perceive({**{k: STATE[k] for k in ("child_name", "period_from", "period_to")},
                      "claims": [dict(c) for c in NOW], "history_claims": [dict(past_center)],
                      "consented_institutions": [{"institution_id": 1, "institution_type": "학교"},
                                                 {"institution_id": 2, "institution_type": "센터"}]})
    same = {**SINGLE, "supports": [{"action": "센터에서 손을 잡고 함께 갔다.", "result": "함께 갔다.",
                                    "claim_ids": ["13-0"]}]}

    def fake(instructions, user, specs, run_tool, max_calls=2, timeout=None, deadline=None):
        run_tool("find_claims", {"situation": "화장실 가기를 거부", "weeks_back": 4})
        return SimpleNamespace(data={"insights": [same]}, usage={"tool_calls": 1})

    saved = (config.INSIGHT_WEAK_MODE, config.INSIGHT_TOOLS, nodes.ask_with_tools)
    config.INSIGHT_WEAK_MODE, config.INSIGHT_TOOLS, nodes.ask_with_tools = "refine", True, fake
    try:
        out = refine(reflect({**state, "raw_insights": [SINGLE], "llm_error": None}))
    finally:
        config.INSIGHT_WEAK_MODE, config.INSIGHT_TOOLS, nodes.ask_with_tools = saved
    assert out["insights"] == [] and out["weak_refined"][0]["why"] == "기관 간 아님", out["weak_refined"]


def test_Luna가_실패하면_버린다():
    out, _ = run(LlmError("시간 초과", "timeout"))
    assert out["insights"] == [] and out["weak_refined"][0]["why"] == "timeout"


def test_도구는_최대_횟수까지만_부른다():
    many = tuple({"situation": "차량", "weeks_back": 4} for _ in range(5))
    _, seen = run({"insights": [CROSS]}, tool_args=many)
    assert len(seen) == config.INSIGHT_TOOL_MAX_CALLS


def test_지난_기록이_없으면_도구_없이_버린다():
    out, seen = run({"insights": [CROSS]}, history=False)
    assert out["insights"] == [] and seen == []
    assert out["weak_dropped"][0]["reasons"][0]["kind"] == "한기관_대처없음"


def test_INSIGHT_TOOLS를_끄면_예전처럼_버린다():
    out, seen = run({"insights": [CROSS]}, tools_on=False)
    assert out["insights"] == [] and seen == []


def test_기본값은_꺼짐이다():
    assert config.INSIGHT_TOOLS is False


if __name__ == "__main__":
    tests = [v for k, v in globals().items() if k.startswith("test_")]
    failed = 0
    for t in tests:
        try:
            t()
            print("PASS", t.__name__)
        except AssertionError as e:
            failed += 1
            print("FAIL", t.__name__, e)
    print(f"\n{len(tests) - failed}/{len(tests)} 통과")
    sys.exit(1 if failed else 0)
