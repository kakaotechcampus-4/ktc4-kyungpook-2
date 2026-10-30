# AI/evals/scripts/summary/test_no_content.py
"""
요약할 내용 없음(no_content) 과 503 이 제대로 갈리는지 검사한다. LLM 을 부르지 않는다.

    python evals/scripts/summary/test_no_content.py

남은 문장이 0 개인 경우는 둘이다 (CRITERIA.md §2).
    모델이 처음부터 빈 claims        → 200 no_content
    문장은 냈는데 근거가 다 탈락     → 503 no_grounded_claims
그리고 claims 키가 없는 응답({})은 모델 고장이라 503 llm_unavailable 이다.
ask_json 을 가짜로 바꿔 모델 답을 정해 두고 run_summary 결과만 본다.
"""

import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from pydantic import ValidationError  # noqa: E402

from summary import nodes  # noqa: E402
from summary.graph import run_summary  # noqa: E402
from summary.llm import LlmError, LlmUnavailable, NoGroundedClaims  # noqa: E402
from summary.prompts import NO_CONTENT_RULE, TONE_RULE, system_message  # noqa: E402
from summary.schemas import SourceEntry, SummaryInput, SummaryOutput  # noqa: E402


def _payload(*contents):
    return SummaryInput(
        child_id=1,
        child_name="아동A",
        entry_date="2026-04-01",
        institution_id=1,
        sources=[
            SourceEntry(journal_entry_id=11 + i, content=c) for i, c in enumerate(contents)
        ],
    )


def _model_says(data):
    """모델이 data 를 답한 것처럼 꾸민다."""
    nodes.ask_json = lambda messages: SimpleNamespace(data=data, usage={})


def _model_dies():
    def boom(messages):
        raise LlmError("가짜 타임아웃")
    nodes.ask_json = boom


def _raises(exc_type, fn):
    try:
        fn()
    except exc_type:
        return True
    except Exception:
        return False
    return False


def case_empty_claims_is_no_content():
    _model_says({"claims": []})
    out = run_summary(_payload("특이사항 없음.", "결석."))
    return (
        out.no_content is True
        and out.content == ""
        and out.claims == []
        and out.covered_entry_ids == []
        and out.uncovered_entry_ids == [11, 12]
    )


def case_missing_claims_is_llm_error():
    _model_says({})
    return _raises(LlmUnavailable, lambda: run_summary(_payload("특이사항 없음.")))


def case_all_dropped_is_503():
    _model_says({"claims": [{
        "text": "오전에 그림을 그렸다.",
        "evidence": [{"journal_entry_id": 11, "quote": "원문에 없는 구절"}],
    }]})
    return _raises(NoGroundedClaims, lambda: run_summary(_payload("오전 블록 놀이에서 혼자 끝까지 쌓음.")))


def case_normal_summary():
    _model_says({"claims": [{
        "text": "오전 블록 놀이에서 혼자 끝까지 쌓았다.",
        "evidence": [{"journal_entry_id": 11, "quote": "혼자 끝까지 쌓음"}],
    }]})
    out = run_summary(_payload("오전 블록 놀이에서 혼자 끝까지 쌓음."))
    return out.no_content is False and len(out.claims) == 1 and out.covered_entry_ids == [11]


def case_model_down_is_llm_error():
    _model_dies()
    return _raises(LlmUnavailable, lambda: run_summary(_payload("특이사항 없음.")))


def case_schema_blocks_bad_no_content():
    return _raises(ValidationError, lambda: SummaryOutput(
        child_id=1, entry_date="2026-04-01", institution_id=1,
        content="글이 있다.", no_content=True,
    ))


def case_prompt_has_rules():
    msg = system_message()
    return NO_CONTENT_RULE in msg and TONE_RULE in msg


CASES = [
    ("빈 claims → 200 no_content", case_empty_claims_is_no_content),
    ("claims 없는 응답 {} → 503 llm_unavailable", case_missing_claims_is_llm_error),
    ("근거 전부 탈락 → 503 no_grounded_claims", case_all_dropped_is_503),
    ("정상 요약 → no_content False", case_normal_summary),
    ("모델 고장 → 503 llm_unavailable", case_model_down_is_llm_error),
    ("no_content 인데 content 있음 → 스키마가 막음", case_schema_blocks_bad_no_content),
    ("프롬프트에 no_content·말투 규칙", case_prompt_has_rules),
]


if __name__ == "__main__":
    original = nodes.ask_json
    failed = 0
    for name, fn in CASES:
        try:
            ok = fn()
        except Exception as exc:  # 예상 못 한 에러도 실패로 센다
            ok = False
            name += f"  ({type(exc).__name__}: {exc})"
        finally:
            nodes.ask_json = original
        print(("PASS " if ok else "FAIL ") + name)
        failed += not ok
    print(f"\n{len(CASES) - failed}/{len(CASES)} 통과")
    sys.exit(1 if failed else 0)