# AI/evals/scripts/summary/test_grounding.py
"""
요약이 환각을 버리는지 LLM 없이 고정한다.

    python evals/scripts/summary/test_grounding.py

모델 응답을 고정해두고 **코드가 거르는 네 단계**(CRITERIA.md §2)만 본다.
입력은 한 기관 것만 들어온다 — 묶음 단위가 아동 × 날짜 × 기관이다 (§1).
실제 모델로는 이걸 잴 수 없다 — 모델이 매번 다른 문장을 쓰기 때문에
"버려야 할 문장" 을 일부러 만들어낼 수가 없다. 매칭의 test_auto_gate.py 와
같은 이유다.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from summary import nodes  # noqa: E402
from summary.graph import run_summary  # noqa: E402
from summary.llm import LlmResult, LlmUnavailable, NoGroundedClaims  # noqa: E402
from main import llm_unavailable  # noqa: E402


class _FakeRequest:
    """핸들러는 경로만 읽어 로그에 쓴다. FastAPI 를 띄우지 않기 위한 대역이다."""

    url = type("U", (), {"path": "/summary"})()


#: 503 의 detail.reason. 상태 코드가 같아도 BE 의 후속 처리가 다르다 (#148).
#:   llm_unavailable      차례를 통째로 멈추고 나중에 다시
#:   no_grounded_claims   그 묶음만 되돌리고 다음으로, 2번 넘으면 FAILED
REASON_CASES = [
    (LlmUnavailable, "llm_unavailable"),
    (NoGroundedClaims, "no_grounded_claims"),
]
from summary.schemas import SourceEntry, SummaryInput  # noqa: E402

#: 한 기관(햇살학교)이 같은 날 올린 기록 둘. 묶음은 기관 안에서만 일어난다.
SOURCES = [
    SourceEntry(
        journal_entry_id=1041,
        content="오전 블록 놀이에서 혼자 다섯 층까지 쌓음. 교사 개입 없이 끝까지 함.",
    ),
    SourceEntry(
        journal_entry_id=1042,
        content="오후 같은 블록 활동에서 3번 교사 손을 잡고 올림. 혼자서는 어려워함.",
    ),
]


def run(claims):
    """모델이 claims 를 내도록 고정해두고 그래프를 한 번 돌린다."""
    original = nodes.ask_json
    nodes.ask_json = lambda messages, **kw: LlmResult(data={"claims": claims})
    try:
        return run_summary(
            SummaryInput(
                child_id=1,
                child_name="김지후",
                entry_date="2026-10-04",
                institution_id=7,
                institution_name="햇살학교",
                sources=SOURCES,
            )
        )
    finally:
        nodes.ask_json = original


def ev(entry_id, quote):
    return {"journal_entry_id": entry_id, "quote": quote}


CASES = [
    (
        "하루의 흐름을 잇는 문장이 근거 둘을 단다",
        [
            {
                "text": "오전에는 혼자 쌓았고 오후에는 도움이 필요했다.",
                "evidence": [
                    ev(1041, "혼자 다섯 층까지 쌓음"),
                    ev(1042, "교사 손을 잡고 올림"),
                ],
            }
        ],
        {"claims": 1, "covered": [1041, 1042], "uncovered": []},
        "한 기관 안에서도 하루에 여러 건이 올라온다. 그것을 잇는 것이 요약이다",
    ),
    (
        "다듬은 인용은 버린다",
        [
            {
                "text": "혼자 블록을 쌓았다.",
                "evidence": [ev(1041, "혼자서 다섯 층까지 쌓았음")],
            }
        ],
        "503",
        "원문은 '혼자 다섯 층까지 쌓음' 이다. 다듬은 인용은 원문이 아니다",
    ),
    (
        "근거가 없는 문장은 버린다",
        [{"text": "오늘 기분이 좋아 보였다.", "evidence": []}],
        "503",
        "지어낸 문장이 그대로 교사에게 가면 안 된다",
    ),
    (
        "숫자를 바꾸면 버린다",
        [
            {
                "text": "교사 손을 잡고 7번 올랐다.",
                "evidence": [ev(1042, "3번 교사 손을 잡고 올림")],
            }
        ],
        "503",
        "인용은 원문에 있지만 문장의 숫자가 근거에 없다",
    ),
    (
        "날짜의 숫자를 횟수 근거로 쓰면 버린다",
        [
            {
                "text": "블록을 4번 쌓았다.",
                "evidence": [ev(1041, "혼자 다섯 층까지 쌓음")],
            }
        ],
        "503",
        "묶음 날짜가 2026-10-04 라고 해서 '4번' 이 근거를 얻으면 안 된다",
    ),
    (
        "날짜 표현은 근거가 없어도 둔다",
        [
            {
                "text": "10월 4일 오전 블록 놀이에 참여했다.",
                "evidence": [ev(1041, "오전 블록 놀이에서 혼자 다섯 층까지 쌓음")],
            }
        ],
        {"claims": 1, "covered": [1041], "uncovered": [1042]},
        "월·일 단위가 붙고 묶음 날짜와 같으면 날짜를 가리킨 것이다",
    ),
    (
        "입력에 없는 기관 일을 지어내면 버린다",
        [
            {
                "text": "늘봄센터에서도 같은 활동을 2번 했다.",
                "evidence": [ev(1042, "교사 손을 잡고 올림")],
            }
        ],
        "503",
        "다른 기관 기록은 애초에 입력에 없다. 숫자 2 가 근거에 없어 걸린다",
    ),
    (
        "다른 일지에서 인용을 찾아주지 않는다",
        [
            {
                "text": "혼자 다섯 층까지 쌓았다.",
                "evidence": [ev(1042, "혼자 다섯 층까지 쌓음")],
            }
        ],
        "503",
        "그 구절은 1041 에 있다. 1042 라고 댔으면 근거가 틀린 것이다",
    ),
    (
        "반영 안 된 일지가 uncovered 로 드러난다",
        [
            {
                "text": "혼자 다섯 층까지 쌓았다.",
                "evidence": [ev(1041, "혼자 다섯 층까지 쌓음")],
            }
        ],
        {"claims": 1, "covered": [1041], "uncovered": [1042]},
        "빠뜨린 기록이 있다는 사실이 사람에게 보여야 한다",
    ),
]


#: 익명화가 실패했는지 표시하는가. (요약 문장, 받은 이름 목록, 기대 review_reasons)
NAME_CASES = [
    ("익명화 성공", "옆자리 아동이 조각을 건네자 받아서 끼웠다.", ["박서연"], []),
    ("이름이 남음", "옆자리 박서연이 조각을 건네자 받아서 끼웠다.", ["박서연"], ["다른아동이름"]),
    (
        "목록이 비면 못 잡는다",
        "옆자리 박서연이 조각을 건네자 받아서 끼웠다.",
        [],
        [],
    ),
]


def run_names(text, names):
    """other_child_names 대조만 보는 작은 실행. 근거는 통과하게 둔다."""
    original = nodes.ask_json
    nodes.ask_json = lambda messages, **kw: LlmResult(
        data={"claims": [{"text": text, "evidence": [ev(1041, "혼자 다섯 층까지 쌓음")]}]}
    )
    try:
        return run_summary(
            SummaryInput(
                child_id=1,
                child_name="김지후",
                entry_date="2026-10-04",
                institution_id=7,
                institution_name="햇살학교",
                sources=SOURCES,
                other_child_names=names,
            )
        )
    finally:
        nodes.ask_json = original


def main():
    print("■ 요약 환각 거르기 (모델 응답 고정, LLM 호출 없음)")
    failed = 0
    for name, claims, want, why in CASES:
        try:
            out = run(claims)
            got = {
                "claims": len(out.claims),
                "covered": out.covered_entry_ids,
                "uncovered": out.uncovered_entry_ids,
            }
        except NoGroundedClaims:
            # 재료가 있는데 남은 문장이 0 이면 빈 요약을 200 으로 주지 않는다.
            # BE 가 빈 claims 를 요약 전체 실패로 보기 때문이다 (#146).
            got = "503"
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {name}")
        print(f"      기대 {want}")
        print(f"      실제 {got} — {why}")
        if not ok:
            failed += 1

    # 본문은 살아남은 문장만으로 만들어진다
    out = run(
        [
            {"text": "혼자 다섯 층까지 쌓았다.", "evidence": [ev(1041, "혼자 다섯 층까지 쌓음")]},
            {"text": "지어낸 문장이다.", "evidence": []},
        ]
    )
    ok = out.content == "혼자 다섯 층까지 쌓았다."
    print(f"  {'✓' if ok else '✗'} 본문은 살아남은 문장만으로 만든다")
    print(f'      실제 content = "{out.content}"')
    if not ok:
        failed += 1

    print("\n■ 다른 아이 이름이 남았는지 표시 (막지는 않는다)")
    for name, text, names, want in NAME_CASES:
        out = run_names(text, names)
        ok = out.review_reasons == want and out.needs_review == bool(want)
        print(f"  {'✓' if ok else '✗'} {name}")
        print(f"      받은 목록 {names} · needs_review={out.needs_review} {out.review_reasons}")
        if not ok:
            failed += 1
    print("      ⚠️ 목록은 ACTIVE 명부 아이만 담긴다. false 가 '안 샌다' 는 뜻이 아니다")

    print("\n■ 503 두 가지를 body 로 구분한다 (BE 후속 처리가 다르다)")
    for exc, want in REASON_CASES:
        got = exc.reason
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {exc.__name__} → detail.reason = \"{got}\"")
        if not ok:
            print(f"      기대 \"{want}\"")
            failed += 1
    # 핸들러가 클래스 값을 읽는지. 문자열을 박아 두면 하위 예외가 묻힌다.
    body = json.loads(
        llm_unavailable(_FakeRequest(), NoGroundedClaims("child_id=1 …")).body
    )
    ok = body["detail"]["reason"] == "no_grounded_claims"
    print(f"  {'✓' if ok else '✗'} 핸들러가 실제로 내보내는 body — {body}")
    if not ok:
        failed += 1
    # 예외 메시지에는 아이 식별자와 Luna 주소가 들어 있다. body 로 나가면 안 된다.
    ok = "child_id" not in json.dumps(body, ensure_ascii=False)
    print(f"  {'✓' if ok else '✗'} 예외 메시지는 body 에 넣지 않는다 (로그로만)")
    if not ok:
        failed += 1

    if failed:
        print(f"\n  실패 {failed}건")
        sys.exit(1)
    print("\n  전부 통과 ✅")


if __name__ == "__main__":
    main()
