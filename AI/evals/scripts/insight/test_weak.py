# AI/evals/scripts/insight/test_weak.py
"""
약한 인사이트 검사(insight/quality.py)와 reflect 의 INSIGHT_WEAK_MODE 단위 테스트. LLM 호출 없음.

    python evals/scripts/insight/test_weak.py

문장은 실데이터를 옮기지 않고 같은 꼴로 새로 썼다 (실데이터는 커밋하지 않는다).
"""

import sys
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from types import SimpleNamespace  # noqa: E402

from insight import config, nodes  # noqa: E402
from insight.llm import LlmError  # noqa: E402
from insight.nodes import perceive, reflect, refine  # noqa: E402
from insight.quality import NOT_IN_RECORD, SINGLE_NO_SUPPORT, weak_reasons  # noqa: E402

TYPES = {1: "학교", 2: "센터"}


def claim(cid, inst, jid):
    return {"claim_id": cid, "text": f"{cid} 문장", "entry_date": f"2026-01-{jid % 28 + 1:02d}",
            "institution_id": inst, "institution_type": TYPES[inst],
            "evidence": [{"journal_entry_id": jid, "quote": "인용"}]}


# 학교 2개(날짜 다름), 센터 1개
STATE = perceive({
    "child_name": "아동 A", "period_from": "2026-01-01", "period_to": "2026-01-31",
    "claims": [claim("10-0", 1, 101), claim("11-0", 2, 102), claim("12-0", 1, 103)],
    "consented_institutions": [{"institution_id": i, "institution_type": t} for i, t in TYPES.items()],
})

CROSS = ["10-0", "11-0"]   # 학교 + 센터
SINGLE = ["10-0", "12-0"]  # 학교만


def raw(ids, content, supports=None):
    return {"situation": "그림책을 읽어 줄 때", "behavior": "교사를 보지 않았다",
            "content": content, "claim_ids": ids, "supports": supports or []}


def support(result="그림을 보고 대답했다."):
    return {"action": "교사가 그림을 짚으며 질문했다.", "result": result, "claim_ids": ["10-0"]}


def run(raws, mode):
    saved = config.INSIGHT_WEAK_MODE
    config.INSIGHT_WEAK_MODE = mode
    try:
        return reflect({**STATE, "raw_insights": raws, "llm_error": None})
    finally:
        config.INSIGHT_WEAK_MODE = saved


def reasons_of(ids, content, supports=None):
    out = run([raw(ids, content, supports)], "off")
    assert len(out["insights"]) == 1, "성립 조건에서 먼저 버려졌다 (테스트 입력 오류)"
    return weak_reasons(out["insights"][0])


GOOD = "학교와 센터에서 그림책을 읽어 줄 때 교사를 보지 않았다. 학교에서는 그림을 짚으며 질문하자 대답했다."
META = "학교와 센터에서 그림책을 읽어 줄 때 교사를 보지 않았다. 센터에서 질문한 뒤의 반응은 기록되지 않았다."


# ── 기록에없는말 ────────────────────────────────────────────────

def test_본문의_기록되지_않았다를_잡고_그_문장을_돌려준다():
    r = reasons_of(CROSS, META)
    assert [x["kind"] for x in r] == [NOT_IN_RECORD], r
    assert r[0]["field"] == "content"
    assert r[0]["sentence"] == "센터에서 질문한 뒤의 반응은 기록되지 않았다."


def test_대처_결과_칸의_말도_칸_이름과_함께_잡는다():
    r = reasons_of(CROSS, GOOD, [support("웃었다. 이후 참여했는지는 기록되지 않았다.")])
    assert [(x["kind"], x["field"]) for x in r] == [(NOT_IN_RECORD, "supports[0].result")], r


def test_기록도_있다_확인되지_않았다_알_수_없다도_잡는다():
    for tail in ("박수에는 참여하지 않은 기록도 있다.", "이유는 확인되지 않았다.", "원인은 알 수 없다."):
        r = reasons_of(CROSS, f"학교와 센터에서 교사를 보지 않았다. {tail}")
        assert [x["kind"] for x in r] == [NOT_IN_RECORD], (tail, r)


def test_기록했다처럼_비슷한_말은_잡지_않는다():
    text = "학교와 센터에서 교사를 보지 않았다. 학교 교사가 대답한 단어를 기록했다."
    assert reasons_of(CROSS, text) == []


# ── 한기관_대처없음 ─────────────────────────────────────────────

def test_한_기관이고_대처가_없으면_잡는다():
    r = reasons_of(SINGLE, "학교에서 그림책을 읽어 줄 때 교사를 보지 않는 날이 이어졌다.")
    assert [x["kind"] for x in r] == [SINGLE_NO_SUPPORT], r


def test_기관_간_비교면_대처가_없어도_잡지_않는다():
    # CRITERIA CROSS_WITHOUT_SUPPORT: 같은 상황에서 기관마다 반응이 다르면 대처 없이도 인사이트다
    assert reasons_of(CROSS, "학교와 센터에서 그림책을 읽어 줄 때 반응이 달랐다.") == []


def test_한_기관이어도_대처가_있으면_잡지_않는다():
    assert reasons_of(SINGLE, "학교에서 그림책을 읽어 줄 때 교사를 보지 않았다.", [support()]) == []


# ── reflect 모드 ────────────────────────────────────────────────

RAWS = [raw(CROSS, GOOD), raw(CROSS, META)]


def test_drop_이면_약한_것만_빼고_이유를_남긴다():
    out = run(RAWS, "drop")
    assert [i.content for i in out["insights"]] == [GOOD]
    assert len(out["weak_dropped"]) == 1 and out["weak_dropped"][0]["content"] == META
    assert out["weak_dropped"][0]["reasons"][0]["kind"] == NOT_IN_RECORD


def test_off_면_빼지_않고_찾은_것만_남긴다():
    out = run(RAWS, "off")
    assert len(out["insights"]) == 2
    assert len(out["weak_found"]) == 1 and out["weak_dropped"] == []


def test_기본값은_off_다():
    assert config.INSIGHT_WEAK_MODE == "off"


# ── refine (C, 수정 루프) — 모델 답을 정해 두고 돌린다 ───────────

FIXED = "학교와 센터에서 그림책을 읽어 줄 때 교사를 보지 않았다. 학교에서는 그림을 짚으며 질문하자 대답했다."


def run_refine(raws, model_answer):
    """reflect → refine 을 refine 모드로. model_answer 가 Exception 이면 Luna 실패로 본다."""
    sent, deadlines = [], []

    def fake_ask(messages, deadline=None):
        sent.append(messages)
        deadlines.append(deadline)
        if isinstance(model_answer, Exception):
            raise model_answer
        return SimpleNamespace(data=model_answer, usage={})

    saved_mode, saved_ask = config.INSIGHT_WEAK_MODE, nodes.ask_json
    config.INSIGHT_WEAK_MODE, nodes.ask_json = "refine", fake_ask
    try:
        out = refine(reflect({**STATE, "raw_insights": raws, "llm_error": None}))
    finally:
        config.INSIGHT_WEAK_MODE, nodes.ask_json = saved_mode, saved_ask
    run_refine.deadlines = deadlines
    return out, sent


def test_refine_고친_것이_통과하면_그_자리를_바꾼다():
    out, sent = run_refine(RAWS, {"insights": [raw(CROSS, FIXED)]})
    assert [i.content for i in out["insights"]] == [GOOD, FIXED]
    assert out["weak_refined"][0]["outcome"] == "rescued" and len(sent) == 1


def test_refine_고쳐도_약하면_버린다():
    out, _ = run_refine(RAWS, {"insights": [raw(CROSS, META)]})
    assert [i.content for i in out["insights"]] == [GOOD]
    assert out["weak_refined"][0] == {"before": META, "after": META, "outcome": "dropped", "why": "여전히 약함"}


def test_refine_처음에_없던_근거를_쓰면_버린다():
    out, _ = run_refine(RAWS, {"insights": [raw(["10-0", "11-0", "12-0"], FIXED)]})
    assert [i.content for i in out["insights"]] == [GOOD]
    assert out["weak_refined"][0]["why"] == "새 근거"


def test_refine_Luna가_실패하면_그것만_버리고_나머지는_낸다():
    out, _ = run_refine(RAWS, LlmError("시간 초과", "timeout"))
    assert [i.content for i in out["insights"]] == [GOOD]
    assert out["weak_refined"][0]["why"] == "timeout"


def test_refine_한기관_대처없음은_부르지_않고_버린다():
    single = raw(SINGLE, "학교에서 그림책을 읽어 줄 때 교사를 보지 않는 날이 이어졌다.")
    out, sent = run_refine([raw(CROSS, GOOD), single], {"insights": [raw(CROSS, FIXED)]})
    assert [i.content for i in out["insights"]] == [GOOD] and sent == []
    assert out["weak_dropped"][0]["reasons"][0]["kind"] == SINGLE_NO_SUPPORT


def test_refine_약한_것이_없으면_부르지_않는다():
    out, sent = run_refine([raw(CROSS, GOOD)], {"insights": []})
    assert [i.content for i in out["insights"]] == [GOOD] and sent == []


def test_refine_요청에는_근거_claim과_문제_문장만_들어간다():
    _, sent = run_refine(RAWS, {"insights": [raw(CROSS, FIXED)]})
    user = sent[0][1]["content"]
    assert "[10-0]" in user and "[11-0]" in user and "[12-0]" not in user  # 처음 근거 밖 claim 은 안 보냄
    assert "센터에서 질문한 뒤의 반응은 기록되지 않았다." in user
    assert "인용" not in user  # 원문 인용(다른 아이 이름이 남아 있음)은 넣지 않는다



def test_refine_한_요청에서_고치는_수는_상한까지만():
    raws = [raw(CROSS, META) for _ in range(config.INSIGHT_REFINE_MAX + 2)]
    out, sent = run_refine(raws, {"insights": [raw(CROSS, FIXED)]})
    assert len(sent) == config.INSIGHT_REFINE_MAX
    assert len(out["weak_dropped"]) == 2 and len(out["insights"]) == config.INSIGHT_REFINE_MAX



def test_refine_시간이_없으면_고치지_않고_버린다():
    saved = config.INSIGHT_DEADLINE
    config.INSIGHT_DEADLINE = 0
    try:
        out, sent = run_refine(RAWS, {"insights": [raw(CROSS, FIXED)]})
    finally:
        config.INSIGHT_DEADLINE = saved
    assert sent == [] and [i.content for i in out["insights"]] == [GOOD]
    assert out["weak_refined"][0]["why"] == "시간 상한"


def test_refine_마감_시각을_Luna_호출에_넘긴다():
    import time
    run_refine(RAWS, {"insights": [raw(CROSS, FIXED)]})
    d = run_refine.deadlines[0]
    assert d is not None and d <= time.monotonic() + config.INSIGHT_DEADLINE


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
