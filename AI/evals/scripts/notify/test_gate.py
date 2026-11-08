# AI/evals/scripts/notify/test_gate.py
"""
발송 관문이 제대로 갈리는지 검사한다. LLM 도 MCP 서버도 부르지 않는다.

    python evals/scripts/notify/test_gate.py

여기서 보는 것은 하나다 — **승인된 인사이트 밖의 내용이 메일로 나가지 않는가** (CRITERIA.md §3).

끝나는 길이 셋이다. 셋이 섞이면 교사가 고칠 수 없는 것을 큐에서 보거나,
고쳐야 할 것이 조용히 재시도만 돌다 끝난다 (graph.py run_notify).
    200 sent / nothing_to_send
    200 blocked          근거 미달. 재시도해도 같다
    503                  모델 고장 · 메일 서버 장애. 재시도하면 될 수 있다
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from notify import nodes  # noqa: E402
from notify.graph import run_notify  # noqa: E402
from notify.llm import LlmError, LlmUnavailable, NotifyDeliveryFailed  # noqa: E402
from notify.mcp_client import McpUnavailable  # noqa: E402
from notify.nodes import (  # noqa: E402
    INSTITUTION_MISMATCH, MISSING_INSIGHT, NO_REFERENCE, NUMBER_MISMATCH, OTHER_CHILD_NAME,
)
from notify.schemas import ApprovedInsight, NotifyInput  # noqa: E402

#: 재료. 센터 기록 하나뿐이고 숫자는 3 뿐이다.
INSIGHT = ApprovedInsight(
    content="김지후는 차량 탑승을 거부했다. 교사가 먼저 타는 것을 보여 주자 3분 뒤 탔다.",
    institution_types=["센터"],
    cross_institution=False,
)


#: 둘째 인사이트. "모델이 한 건을 통째로 빼먹었다" 를 재는 데만 쓴다.
SECOND = ApprovedInsight(
    content="김지후는 간식 시간에 새로운 음식을 밀어냈다. 교사가 한 입만 권하자 받아들였다.",
    institution_types=["센터"],
    cross_institution=False,
)


def _payload(*, insights=(INSIGHT,), notification_id="run-1", others=("박서연",)):
    return NotifyInput(
        child_id=12, child_name="김지후",
        period_from="2026-04-01", period_to="2026-04-07",
        insights=list(insights), notification_id=notification_id,
        other_child_names=list(others),
    )


class _Calls(list):
    """send_email 이 실제로 불렸는지 센다. 막혔는데 불리면 아이 기록이 나간 것이다."""

    def tool(self, name, args):
        self.append((name, args))
        return {"status": "sent", "message_id": "itda-test", "duplicate": False}


def _stub(lines, *, subject="이번 주 김지후", calls=None, tool=None):
    nodes.ask_json = lambda messages, **kw: type(
        "R", (), {"data": {"subject": subject, "lines": lines}, "usage": {}})()
    nodes.call_tool = tool or (calls.tool if calls is not None else None)


# ── 통과 ────────────────────────────────────────────────────────


def case_grounded_mail_is_sent():
    calls = _Calls()
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0},
           {"text": "선생님이 먼저 타는 걸 보여 주자 3분 뒤에 탔어요.", "insight_index": 0}], calls=calls)
    out = run_notify(_payload())
    return (out.status == "sent" and out.message_id == "itda-test"
            and len(calls) == 1 and calls[0][0] == "send_email"
            # 수신자는 도구 인자에 없다 — child_id 로 서버가 찾는다
            and "to" not in calls[0][1] and "email" not in calls[0][1])


def case_no_insight_skips_model():
    """승인된 인사이트가 없으면 모델을 부르지 않는다. 빈 재료로 부르면 지어낸다."""
    called = []
    nodes.ask_json = lambda *a, **kw: called.append(1)
    nodes.call_tool = lambda *a, **kw: called.append(2)
    out = run_notify(_payload(insights=()))
    return out.status == "nothing_to_send" and not called and not out.llm_called


def case_duplicate_is_not_resent():
    """같은 묶음으로 다시 와도 메일은 한 번만. BE 재시도가 메일 세 통이 되면 안 된다."""
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0}],
          tool=lambda n, a: {"status": "sent", "message_id": "itda-test", "duplicate": True})
    out = run_notify(_payload())
    return out.status == "sent" and out.duplicate is True


# ── 막힘 (200 blocked · 발송 안 함) ─────────────────────────────


def _blocked(lines, reason):
    calls = _Calls()
    _stub(lines, calls=calls)
    out = run_notify(_payload())
    return (out.status == "blocked"
            and not calls  # ← 핵심. 막혔으면 도구가 불리면 안 된다
            and out.message_id is None
            and any(reason in b.reasons for b in out.blocked_lines))


def case_invented_number_is_blocked():
    return _blocked([{"text": "김지후는 7분 뒤에 탔어요.", "insight_index": 0}], NUMBER_MISMATCH)


def case_invented_institution_is_blocked():
    """센터 기록뿐인 인사이트에 '학교에서는' 을 붙이면 보호자가 사실을 잘못 안다."""
    return _blocked([{"text": "학교에서는 잘 지냈어요.", "insight_index": 0}], INSTITUTION_MISMATCH)


def case_other_child_name_is_blocked():
    """메일은 기관 경계 밖으로 나가는 유일한 길이다."""
    return _blocked([{"text": "박서연이와 함께 탔어요.", "insight_index": 0}], OTHER_CHILD_NAME)


def case_missing_reference_is_blocked():
    """어느 인사이트에서 나왔는지 안 가리키면 대조할 상대가 없다."""
    return _blocked([{"text": "한 주 동안 잘 지냈어요."}], NO_REFERENCE)


def case_out_of_range_reference_is_blocked():
    return _blocked([{"text": "잘 지냈어요.", "insight_index": 9}], NO_REFERENCE)


def case_one_bad_line_blocks_whole_mail():
    """걸린 줄만 빼고 보내지 않는다 — 한 줄 빠진 메일은 교사가 승인한 적 없는 조합이다."""
    calls = _Calls()
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0},
           {"text": "김지후는 99분이나 울었어요.", "insight_index": 0}], calls=calls)
    out = run_notify(_payload())
    return out.status == "blocked" and not calls and len(out.blocked_lines) == 1


# ── 503 (재시도하면 될 수 있다) ────────────────────────────────


def case_model_down_is_503():
    nodes.ask_json = lambda *a, **kw: (_ for _ in ()).throw(LlmError("죽음", "timeout"))
    nodes.call_tool = lambda *a, **kw: None
    try:
        run_notify(_payload())
    except NotifyDeliveryFailed:
        return False  # 모델 고장을 발송 실패로 보고하면 BE 가 엉뚱하게 처리한다
    except LlmUnavailable as exc:
        return exc.reason == "llm_unavailable" and exc.failure == "timeout"
    return False


def case_delivery_failure_is_503_not_blocked():
    """메일 서버 장애 ≠ 근거 미달. 전자는 재시도, 후자는 교사 확인이다."""
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0}],
          tool=lambda n, a: (_ for _ in ()).throw(McpUnavailable("메일 서버 죽음")))
    try:
        run_notify(_payload())
    except NotifyDeliveryFailed as exc:
        return exc.reason == "delivery_failed"
    except LlmUnavailable:
        return False
    return False


def case_no_recipient_is_not_sent():
    """
    받을 사람이 없는 것은 장애가 아니지만 **메일도 안 나갔다**. sent 로 묶으면 BE 가 SENT 로 적고,
    교사는 보냈다고 믿는다 (리뷰 #430).
    """
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0}],
          tool=lambda n, a: {"status": "no_recipient", "message_id": None, "duplicate": False})
    out = run_notify(_payload())
    return out.status == "no_recipient" and out.message_id is None and not out.blocked_lines


def case_subject_with_other_child_name_is_blocked():
    """제목은 받은편지함 목록에 제일 먼저 보인다 — 열지 않아도 샌다 (리뷰 #430)."""
    calls = _Calls()
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0}],
          subject="김지후와 박서연의 한 주", calls=calls)
    out = run_notify(_payload())
    return (out.status == "blocked" and not calls
            and any(OTHER_CHILD_NAME in b.reasons for b in out.blocked_lines))


def case_subject_with_invented_number_is_blocked():
    calls = _Calls()
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0}],
          subject="김지후 99번의 순간", calls=calls)
    out = run_notify(_payload())
    return (out.status == "blocked" and not calls
            and any(NUMBER_MISMATCH in b.reasons for b in out.blocked_lines))


def case_subject_may_use_period_dates():
    """기간 날짜는 인사이트에 없어도 제목에 쓸 수 있다 — 기간을 적는 것이 정상이다."""
    calls = _Calls()
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0}],
          subject="김지후의 2026-04-01 ~ 2026-04-07 기록", calls=calls)
    out = run_notify(_payload())
    return out.status == "sent" and len(calls) == 1


def case_missing_insight_is_blocked():
    """한 줄이 빠지면 막으면서 한 건이 통째로 빠지는 것은 통과시킬 수 없다 (리뷰 #430)."""
    calls = _Calls()
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0}], calls=calls)
    out = run_notify(_payload(insights=(INSIGHT, SECOND)))
    return (out.status == "blocked" and not calls
            and any(MISSING_INSIGHT in b.reasons for b in out.blocked_lines))


def case_all_insights_covered_is_sent():
    calls = _Calls()
    _stub([{"text": "김지후는 차량 타기를 힘들어했어요.", "insight_index": 0},
           {"text": "새로운 음식을 밀어냈지만 한 입 권하자 받아들였어요.", "insight_index": 1}], calls=calls)
    out = run_notify(_payload(insights=(INSIGHT, SECOND)))
    return out.status == "sent" and len(calls) == 1


CASES = [
    ("근거 있는 메일 → sent · 도구 인자에 주소 없음", case_grounded_mail_is_sent),
    ("승인된 인사이트 없음 → 모델을 안 부른다", case_no_insight_skips_model),
    ("같은 묶음 재시도 → duplicate", case_duplicate_is_not_resent),
    ("받을 사람 없음 → no_recipient (sent 아님)", case_no_recipient_is_not_sent),
    ("인사이트 둘 다 담김 → sent", case_all_insights_covered_is_sent),
    ("제목에 기간 날짜 → 통과", case_subject_may_use_period_dates),
    ("제목에 다른 아이 이름 → blocked", case_subject_with_other_child_name_is_blocked),
    ("제목에 지어낸 숫자 → blocked", case_subject_with_invented_number_is_blocked),
    ("인사이트 한 건 누락 → blocked", case_missing_insight_is_blocked),
    ("지어낸 숫자 → blocked · 발송 안 함", case_invented_number_is_blocked),
    ("없는 기관 → blocked · 발송 안 함", case_invented_institution_is_blocked),
    ("다른 아이 이름 → blocked · 발송 안 함", case_other_child_name_is_blocked),
    ("근거 안 가리킴 → blocked", case_missing_reference_is_blocked),
    ("범위 밖 근거 번호 → blocked", case_out_of_range_reference_is_blocked),
    ("한 줄만 걸려도 메일 전체가 막힘", case_one_bad_line_blocks_whole_mail),
    ("모델 고장 → 503 llm_unavailable", case_model_down_is_503),
    ("발송 실패 → 503 delivery_failed (blocked 아님)", case_delivery_failure_is_503_not_blocked),
]


if __name__ == "__main__":
    original_ask, original_tool = nodes.ask_json, nodes.call_tool
    failed = 0
    for name, fn in CASES:
        try:
            ok = fn()
        except Exception as exc:  # 예상 못 한 에러도 실패로 센다
            ok = False
            name += f"  ({type(exc).__name__}: {exc})"
        finally:
            nodes.ask_json, nodes.call_tool = original_ask, original_tool
        print(("PASS " if ok else "FAIL ") + name)
        failed += not ok
    print(f"\n{len(CASES) - failed}/{len(CASES)} 통과")
    sys.exit(1 if failed else 0)
