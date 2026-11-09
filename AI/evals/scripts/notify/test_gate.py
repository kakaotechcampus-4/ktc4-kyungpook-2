import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from notify import nodes  # noqa: E402
from notify.graph import run_notify  # noqa: E402
from notify.llm import NotifyDeliveryFailed  # noqa: E402
from notify.mcp_client import McpUnavailable  # noqa: E402
from notify.nodes import OTHER_CHILD_NAME  # noqa: E402
from notify.schemas import (  # noqa: E402
    ApprovedInsight, EvidenceEntry, EvidenceSentence, NotifyInput,
)

INSIGHT = ApprovedInsight(
    content="센터에서는 차량 타기를 힘들어했지만, 교사가 먼저 타는 모습을 보여 주자 3분 뒤에 탔어요.",
    institution_name="센터1",
    period_label="2026년 4월 1주차",
    institution_types=["센터"],
    cross_institution=False,
    evidence=[EvidenceEntry(institution_name="센터1", date="2026-04-01", sentences=[
        EvidenceSentence(text="차량 앞에서 멈춰 섰다.", role="상황"),
        EvidenceSentence(text="교사가 먼저 타자 3분 뒤 탔다.", role="대처"),
    ])],
)
SECOND = ApprovedInsight(
    content="간식 시간에 새로운 음식을 밀어냈지만, 한 입만 권하자 받아들였어요.",
    institution_types=["센터"],
    cross_institution=False,
)
WITH_NAME = ApprovedInsight(
    content="자유놀이 시간에 박서연이 쌓은 블록을 무너뜨렸지만, 순서를 정해 주자 번갈아 쌓았어요.",
    institution_name="센터1",
    period_label="2026년 4월 1주차",
    institution_types=["센터"],
    cross_institution=False,
)

NAME_IN_EVIDENCE = ApprovedInsight(
    content="자유놀이 시간에 블록을 쌓았어요.",
    institution_name="센터1",
    period_label="2026년 4월 1주차",
    institution_types=["센터"],
    cross_institution=False,
    evidence=[EvidenceEntry(institution_name="센터1", date="2026-04-01", sentences=[
        EvidenceSentence(text="박서연이 쌓은 블록을 무너뜨렸다.", role="상황"),
    ])],
)


def _payload(*, insights=(INSIGHT,), notification_id="run-1", others=("박서연", "이준호"), name="김지후"):
    return NotifyInput(
        child_id=12, child_name=name,
        period_from="2026-04-01", period_to="2026-04-07",
        insights=list(insights), notification_id=notification_id,
        other_child_names=list(others),
    )


class _Calls(list):

    def tool(self, name, args):
        self.append((name, args))
        return {"status": "sent", "message_id": "itda-test", "duplicate": False}


def case_sends_approved_text_verbatim():
    calls = _Calls()
    nodes.call_tool = calls.tool
    out = run_notify(_payload(insights=(INSIGHT, SECOND)))
    body = calls[0][1]["body"]
    return (out.status == "sent" and len(calls) == 1
            and INSIGHT.content in body and SECOND.content in body
            and "to" not in calls[0][1] and "email" not in calls[0][1])


def case_body_has_report_shape():
    calls = _Calls()
    nodes.call_tool = calls.tool
    run_notify(_payload())
    body = calls[0][1]["body"]
    return all(x in body for x in (
        "센터1 · 2026년 4월 1주차", "기관들이 함께 본 것", "이 글의 근거",
        "4월 1일 일지", "(상황) 차량 앞에서 멈춰 섰다.", "(대처) 교사가 먼저 타자 3분 뒤 탔다."))


def case_html_is_built_and_escaped():
    calls = _Calls()
    nodes.call_tool = calls.tool
    run_notify(_payload())
    html = calls[0][1]["html"]
    return (html.startswith("<!DOCTYPE html>")
            and "센터1" in html and "2026년 4월 1주차" in html
            and "차량 앞에서 멈춰 섰다." in html
            and "img/logo.png" in html and "parent/report" in html)


def case_html_escapes_markup():
    calls = _Calls()
    nodes.call_tool = calls.tool
    risky = ApprovedInsight(
        content="<b>굵게</b> & 기호가 들어간 기록이에요.",
        institution_name="센터1", period_label="2026년 4월 1주차",
        institution_types=["센터"], cross_institution=False)
    run_notify(_payload(insights=(risky,)))
    html = calls[0][1]["html"]
    return "&lt;b&gt;굵게&lt;/b&gt;" in html and "&amp;" in html


def case_evidence_name_is_blocked():
    calls = _Calls()
    nodes.call_tool = calls.tool
    out = run_notify(_payload(insights=(NAME_IN_EVIDENCE,)))
    return (out.status == "blocked" and not calls
            and any(OTHER_CHILD_NAME in b.reasons for b in out.blocked_lines))


def case_subject_has_child_and_period():
    calls = _Calls()
    nodes.call_tool = calls.tool
    run_notify(_payload())
    subject = calls[0][1]["subject"]
    return "김지후" in subject and "4월 1일" in subject and "4월 7일" in subject


def case_no_insight_sends_nothing():
    calls = _Calls()
    nodes.call_tool = calls.tool
    out = run_notify(_payload(insights=()))
    return out.status == "nothing_to_send" and not calls


def case_no_recipient_is_not_sent():
    nodes.call_tool = lambda n, a: {"status": "no_recipient", "message_id": None, "duplicate": False}
    out = run_notify(_payload())
    return out.status == "no_recipient" and out.message_id is None and not out.blocked_lines


def case_duplicate_is_not_resent():
    nodes.call_tool = lambda n, a: {"status": "sent", "message_id": "itda-test", "duplicate": True}
    out = run_notify(_payload())
    return out.status == "sent" and out.duplicate is True


def case_other_child_name_is_blocked():
    calls = _Calls()
    nodes.call_tool = calls.tool
    out = run_notify(_payload(insights=(WITH_NAME,)))
    return (out.status == "blocked"
            and not calls
            and out.message_id is None
            and any(OTHER_CHILD_NAME in b.reasons for b in out.blocked_lines))


def case_one_bad_line_blocks_whole_mail():
    calls = _Calls()
    nodes.call_tool = calls.tool
    out = run_notify(_payload(insights=(INSIGHT, WITH_NAME)))
    return out.status == "blocked" and not calls and len(out.blocked_lines) == 1


def case_name_in_subject_is_blocked():
    calls = _Calls()
    nodes.call_tool = calls.tool
    out = run_notify(_payload(name="박서연"))
    return out.status == "blocked" and not calls


def case_unrelated_name_passes():
    calls = _Calls()
    nodes.call_tool = calls.tool
    out = run_notify(_payload(insights=(WITH_NAME,), others=("최하늘",)))
    return out.status == "sent" and len(calls) == 1


def case_delivery_failure_is_503_not_blocked():
    nodes.call_tool = lambda n, a: (_ for _ in ()).throw(McpUnavailable("메일 서버 죽음"))
    try:
        run_notify(_payload())
    except NotifyDeliveryFailed as exc:
        return exc.reason == "delivery_failed"
    return False


CASES = [
    ("승인된 글자가 그대로 나간다 · 도구 인자에 주소 없음", case_sends_approved_text_verbatim),
    ("제목에 아이 이름과 기간", case_subject_has_child_and_period),
    ("메일이 리포트 모양이다 — 기관·본문·근거(상황/대처)", case_body_has_report_shape),
    ("HTML 본문이 함께 만들어진다", case_html_is_built_and_escaped),
    ("HTML 이 기록 속 태그·기호를 이스케이프한다", case_html_escapes_markup),
    ("근거 문장에 다른 아이 이름 → blocked", case_evidence_name_is_blocked),
    ("승인된 인사이트 없음 → 아무것도 안 보낸다", case_no_insight_sends_nothing),
    ("받을 사람 없음 → no_recipient (sent 아님)", case_no_recipient_is_not_sent),
    ("같은 묶음 재시도 → duplicate", case_duplicate_is_not_resent),
    ("승인된 글에 다른 아이 이름 → blocked · 발송 안 함", case_other_child_name_is_blocked),
    ("한 줄만 걸려도 메일 전체가 막힘", case_one_bad_line_blocks_whole_mail),
    ("제목에 다른 아이 이름 → blocked", case_name_in_subject_is_blocked),
    ("목록에 없는 이름은 통과", case_unrelated_name_passes),
    ("발송 실패 → 503 delivery_failed (blocked 아님)", case_delivery_failure_is_503_not_blocked),
]


if __name__ == "__main__":
    original = nodes.call_tool
    failed = 0
    for name, fn in CASES:
        try:
            ok = fn()
        except Exception as exc:
            ok = False
            name += f"  ({type(exc).__name__}: {exc})"
        finally:
            nodes.call_tool = original
        print(("PASS " if ok else "FAIL ") + name)
        failed += not ok
    print(f"\n{len(CASES) - failed}/{len(CASES)} 통과")
    sys.exit(1 if failed else 0)
