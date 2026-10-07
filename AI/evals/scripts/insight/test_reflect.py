# AI/evals/scripts/insight/test_reflect.py
"""
인사이트 인식(perceive)·계획(plan)·반영(reflect) 규칙 단위 테스트. LLM 호출 없음.

모델 응답을 손으로 만들어 넣고, CRITERIA §4(성립 조건)와 §7(재료 쪽 동의)대로
거르는지 본다. 코드를 고칠 때마다 먼저 돌린다.
"""

import sys
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from insight import config  # noqa: E402
from insight.nodes import perceive, plan, reflect  # noqa: E402
from insight.prompts import build_messages  # noqa: E402

TYPES = {1: "학교", 2: "센터", 3: "학원"}


def claim(cid: str, inst: int, jid: int) -> dict:
    return {
        "claim_id": cid, "text": f"{cid} 문장", "entry_date": "2026-01-08",
        "institution_id": inst, "institution_type": TYPES[inst],
        "evidence": [{"journal_entry_id": jid, "quote": "인용"}],
    }


# 학교 2개, 센터 1개
CLAIMS = [claim("10-0", 1, 101), claim("11-0", 2, 102), claim("12-0", 1, 103)]


def make_state(claims=CLAIMS, consented=(1, 2, 3)) -> dict:
    return perceive({
        "claims": [dict(c) for c in claims],
        "consented_institutions": [{"institution_id": i, "institution_type": TYPES[i]} for i in consented],
    })


def insight(ids, content="같은 상황에서 같은 반응이 반복됐다", situation="상황", behavior="반응", supports=None) -> dict:
    return {"situation": situation, "behavior": behavior, "content": content,
            "claim_ids": ids, "supports": supports or []}


def run(state: dict, raw: list) -> list:
    return reflect({**state, "raw_insights": raw, "llm_error": None})["insights"]


# ── §4 성립 조건 ────────────────────────────────────────────────

def test_정상_인사이트는_남고_출처를_코드가_채운다():
    out = run(make_state(), [insight(["10-0", "11-0"])])
    assert len(out) == 1
    assert out[0].source_institution_ids == [1, 2]
    assert out[0].source_entry_ids == [101, 102]
    assert out[0].cross_institution is True


def test_입력에_없는_번호를_가리키면_버린다():
    assert run(make_state(), [insight(["10-0", "99-9"])]) == []


def test_근거가_하나면_버린다():
    assert run(make_state(), [insight(["10-0"])]) == []


def test_상황이_비어_있으면_버린다():
    assert run(make_state(), [insight(["10-0", "11-0"], situation="")]) == []


def test_근거에_없는_기관을_언급하면_버린다():
    # 근거는 학교 두 개뿐인데 본문에 센터
    out = run(make_state(), [insight(["10-0", "12-0"], content="학교에서는 혼자, 센터에서는 도움이 필요했다")])
    assert out == []


def test_입력에_없는_기관_종류를_언급하면_버린다():
    out = run(make_state(), [insight(["10-0", "11-0"], content="학원에서도 같은 모습이었다")])
    assert out == []


def test_근거_기관을_언급하면_남는다():
    out = run(make_state(), [insight(["10-0", "11-0"], content="학교에서는 혼자, 센터에서는 도움이 필요했다")])
    assert len(out) == 1


def test_문제있는_대처만_빠진다():
    supports = [
        {"action": "그림 카드", "result": "돌아옴", "claim_ids": ["10-0"]},           # 남음
        {"action": "이름 부름", "result": "거부", "claim_ids": []},                   # 번호 없음
        {"action": "타이머", "result": "", "claim_ids": ["10-0"]},                    # 결과 없음
        {"action": "센터에서 안아줌", "result": "진정", "claim_ids": ["10-0"]},       # 근거는 학교
    ]
    out = run(make_state(), [insight(["10-0", "11-0"], supports=supports)])
    assert len(out) == 1
    assert [s.action for s in out[0].supports] == ["그림 카드"]


# ── §7 재료 쪽 동의 ─────────────────────────────────────────────

def test_동의_밖_기관_claim은_재료에서_빠진다():
    state = make_state(consented=(1,))  # 센터 철회
    assert "11-0" not in state["claim_index"]
    assert run(state, [insight(["10-0", "11-0"])]) == []          # 센터 근거를 쓴 인사이트는 버림
    assert len(run(state, [insight(["10-0", "12-0"])])) == 1      # 학교만 쓴 인사이트는 남음


def test_동의_목록이_비면_아무_claim도_쓰지_않는다():
    state = plan(make_state(consented=()))
    assert state["claims"] == []
    assert state["skip_llm"] is True

# ── 근거 수 세는 법 (CRITERIA §10 미결) ─────────────────────────

# 같은 날 학교·센터 claim 두 개. claim() 의 날짜는 모두 2026-01-08 이다.
SAME_DAY = [claim("20-0", 1, 201), claim("20-50", 2, 202)]


def test_지금은_같은_날_두_claim도_근거_2개로_센다():
    # 결정이 바뀌면 이 테스트를 CRITERIA §10 과 같이 바꾼다
    assert config.COUNT_DISTINCT_DATES is False
    assert len(run(make_state(claims=SAME_DAY), [insight(["20-0", "20-50"])])) == 1


def test_COUNT_DISTINCT_DATES를_켜면_같은_날은_1개로_센다():
    original = config.COUNT_DISTINCT_DATES
    try:
        config.COUNT_DISTINCT_DATES = True
        assert run(make_state(claims=SAME_DAY), [insight(["20-0", "20-50"])]) == []
    finally:
        config.COUNT_DISTINCT_DATES = original  # 다른 테스트에 영향이 없게 되돌린다

# ── §4·§7 쓸모 태그 ─────────────────────────────────────────────

def test_쓸모_태그는_목록_밖_값만_빠지고_중복이_없다():
    raw = insight(["10-0", "11-0"])
    raw["relevant_institution_types"] = ["학교", "어린이집", "센터", "학교"]
    raw["relevance_reason"] = "활동 전환은 학교와 센터 모두에서 일어난다"
    out = run(make_state(), [raw])
    assert len(out) == 1                                   # 틀린 값이 있어도 인사이트는 남음
    assert out[0].relevant_institution_types == ["학교", "센터"]
    assert out[0].relevance_reason != ""


def test_쓸모_태그는_근거_기관이_아니어도_남는다():
    raw = insight(["10-0", "12-0"])                         # 근거는 학교뿐
    raw["relevant_institution_types"] = ["학원"]
    raw["relevance_reason"] = "학원에서도 수업 전환이 있다"
    out = run(make_state(), [raw])
    assert out[0].relevant_institution_types == ["학원"]   # 상황으로 판단한 값이라 남김

# ── §2 인용 비노출 ──────────────────────────────────────────────

def test_프롬프트에_인용이_들어가지_않는다():
    # text 는 요약이 익명화했고, quote 는 원문이라 다른 아이 이름이 남아 있다
    named = claim("30-0", 1, 301)
    named["text"] = "옆자리 아동이 과자를 나눠 주자 받아 먹었다."
    named["evidence"] = [{"journal_entry_id": 301, "quote": "옆자리 서준호가 과자를 나눠 주자 받아서 먹음"}]

    state = make_state(claims=[named, claim("31-0", 1, 302)])
    state.update(child_name="테스트", period_from="2026-01-01", period_to="2026-01-31")
    prompt = " ".join(m["content"] for m in build_messages(state))

    assert "서준호" not in prompt                 # 인용 속 이름은 안 들어감
    assert "옆자리 아동이 과자를" in prompt        # claim 문장은 들어감
        
# ── 계획 ────────────────────────────────────────────────────────

def test_claim이_최소_근거_수보다_적으면_모델을_부르지_않는다():
    assert plan(make_state(claims=CLAIMS[:1]))["skip_llm"] is True
    assert plan(make_state())["skip_llm"] is False


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