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

TYPES = {1: "학교", 2: "센터", 3: "활동지원사"}


def claim(cid: str, inst: int, jid: int, date: str | None = None) -> dict:
    # 일지마다 다른 날로 둔다. 같은 날을 시험할 때만 date 를 넘긴다.
    return {
        "claim_id": cid, "text": f"{cid} 문장", "entry_date": date or f"2026-01-{jid % 28 + 1:02d}",
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
    out = run(make_state(), [insight(["10-0", "11-0"], content="활동지원사와 있을 때도 같은 모습이었다")])
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

DAY = "2026-01-08"
# 같은 날 학교·센터 — 기관 간 비교라 2개로 센다
SAME_DAY_TWO_INST = [claim("20-0", 1, 201, DAY), claim("20-50", 2, 202, DAY)]
# 같은 날 같은 기관 — 요약 한 편에서 나온 문장 둘이라 1개로 센다
SAME_RECORD = [claim("21-0", 1, 211, DAY), claim("21-1", 1, 212, DAY)]


def test_같은_날_다른_기관은_근거_2개로_센다():
    assert config.COUNT_DISTINCT_RECORDS is True
    assert len(run(make_state(claims=SAME_DAY_TWO_INST), [insight(["20-0", "20-50"])])) == 1


def test_같은_날_같은_기관_문장_둘은_근거_1개라_버린다():
    assert run(make_state(claims=SAME_RECORD), [insight(["21-0", "21-1"])]) == []


def test_COUNT_DISTINCT_RECORDS를_끄면_문장_수로_센다():
    original = config.COUNT_DISTINCT_RECORDS
    try:
        config.COUNT_DISTINCT_RECORDS = False
        assert len(run(make_state(claims=SAME_RECORD), [insight(["21-0", "21-1"])])) == 1
    finally:
        config.COUNT_DISTINCT_RECORDS = original  # 다른 테스트에 영향이 없게 되돌린다

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

# ── 교사 작성 문장 (#199) ───────────────────────────────────────

from pydantic import ValidationError  # noqa: E402

from insight.schemas import InsightClaim  # noqa: E402


def teacher_claim(cid: str, inst: int) -> dict:
    text = f"{cid} 교사 문장"
    return {
        "claim_id": cid, "text": text, "entry_date": "2026-01-09",
        "institution_id": inst, "institution_type": TYPES[inst], "source": "TEACHER",
        "evidence": [{"journal_entry_id": None, "quote": text}],
    }


def test_source_를_안_보내면_AI_다():
    assert InsightClaim(**claim("10-0", 1, 101)).source == "AI"


def test_교사_문장은_일지_번호_없이_통과한다():
    assert InsightClaim(**teacher_claim("13-0", 2)).evidence[0].journal_entry_id is None


def test_AI_문장인데_일지_번호가_없으면_막는다():
    broken = {**claim("10-0", 1, 101), "evidence": [{"journal_entry_id": None, "quote": "인용"}]}
    try:
        InsightClaim(**broken)
    except ValidationError:
        return
    raise AssertionError("AI 문장의 끊긴 근거가 통과했다")


def test_교사_문장이_섞여도_출처_일지에서는_빠진다():
    claims = CLAIMS + [teacher_claim("13-0", 2)]
    out = run(make_state(claims=claims), [insight(["10-0", "13-0"])])
    assert len(out) == 1
    assert out[0].source_entry_ids == [101]          # 교사 문장은 일지가 없다
    assert out[0].source_institution_ids == [1, 2]   # 기관에는 잡힌다

def edited_claim(cid: str, inst: int, jid: int) -> dict:
    return {**claim(cid, inst, jid), "source": "EDITED"}


def test_AI_문장만이면_교사_표시가_없다():
    out = run(make_state(), [insight(["10-0", "11-0"])])
    assert out[0].teacher_claim_ids == []


def test_교사_문장이_섞이면_번호를_표시한다():
    claims = CLAIMS + [teacher_claim("13-0", 2)]
    out = run(make_state(claims=claims), [insight(["10-0", "13-0"])])
    assert out[0].teacher_claim_ids == ["13-0"]


def test_교사_문장만으로는_인사이트를_만들지_않는다():
    claims = CLAIMS + [teacher_claim("13-0", 2), teacher_claim("14-0", 1)]
    assert run(make_state(claims=claims), [insight(["13-0", "14-0"])]) == []


def test_고친_문장은_원문으로_세지_않지만_섞이면_표시한다():
    claims = CLAIMS + [edited_claim("15-0", 1, 104), edited_claim("16-0", 2, 105)]
    state = make_state(claims=claims)
    assert run(state, [insight(["15-0", "16-0"])]) == []
    out = run(state, [insight(["10-0", "15-0"])])
    assert out[0].teacher_claim_ids == ["15-0"]


def test_대처_근거의_교사_문장도_표시한다():
    claims = CLAIMS + [teacher_claim("13-0", 2)]
    support = {"action": "순서를 미리 알려줌", "result": "차분히 기다림", "claim_ids": ["12-0", "13-0"]}
    out = run(make_state(claims=claims), [insight(["10-0", "11-0"], supports=[support])])
    assert len(out[0].supports) == 1
    assert out[0].teacher_claim_ids == ["13-0"]
    

def test_교사_문장만으로_된_대처는_버리고_인사이트는_남긴다():
    claims = CLAIMS + [teacher_claim("13-0", 2)]
    support = {"action": "순서를 미리 알려줌", "result": "차분히 기다림", "claim_ids": ["13-0"]}
    out = run(make_state(claims=claims), [insight(["10-0", "11-0"], supports=[support])])
    assert len(out) == 1
    assert out[0].supports == []
    assert out[0].teacher_claim_ids == []


def test_대처에만_쓰인_기관도_출처에_들어간다():
    claims = CLAIMS + [claim("17-0", 3, 106)]   # 활동지원사 claim 은 대처 근거로만
    support = {"action": "순서를 미리 알려줌", "result": "차분히 기다림", "claim_ids": ["17-0"]}
    out = run(make_state(claims=claims), [insight(["10-0", "12-0"], supports=[support])])
    assert out[0].source_institution_ids == [1, 3]
    assert out[0].source_entry_ids == [101, 103, 106]
    assert out[0].cross_institution is False      # 기관 간 차이 여부는 본문 근거(학교 둘)로만

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