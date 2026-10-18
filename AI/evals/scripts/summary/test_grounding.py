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
from summary.nodes import _counts, _numbers, _numbers_outside_dates  # noqa: E402
from summary.graph import run_summary  # noqa: E402
from summary.llm import LlmResult, LlmUnavailable, NoGroundedClaims  # noqa: E402
from main import llm_unavailable  # noqa: E402


class _FakeRequest:
    """핸들러는 경로만 읽어 로그에 쓴다. FastAPI 를 띄우지 않기 위한 대역이다."""

    url = type("U", (), {"path": "/summary"})()


#: 503 의 detail.reason. 상태 코드가 같아도 BE 의 후속 처리가 다르다 (#148).
#:   llm_unavailable      차례를 통째로 멈추고 나중에 다시
#:   no_grounded_claims   그 묶음만 되돌리고 다음으로, 2번 넘으면 FAILED
#: 날짜 숫자 면제. **값이 아니라 자리**를 지운다 (2026-10-11 멘토 리뷰 P2).
#: 묶음 날짜는 2026-10-07 기준이다.
DATE_CASES = [
    ("날짜이자 횟수 — 횟수는 근거를 본다", "10월 7일, 공을 7번 주고받았다.", "공을 주고받음", False),
    ("날짜이자 횟수 — 근거가 있으면 통과", "10월 7일, 공을 7번 주고받았다.", "공을 7번 주고받음", True),
    ("날짜만 있으면 통과", "10월 7일 기록이다.", "기록", True),
    ("단위 없는 숫자는 근거를 본다", "공을 7번 주고받았다.", "공을 주고받음", False),
    ("시각은 관찰 내용이다", "2026년 10월 7일 오전 10시에 왔다.", "오전에 옴", False),
    ("연·월·일 전부 묶음 날짜면 통과", "2026년 10월 7일에 왔다.", "왔다", True),
    ("다른 날짜는 원문에 있어야 한다", "8월 21일 관찰분이다.", "관찰", False),
    ("층수에 근거가 있으면 통과", "블록을 5층까지 쌓았다.", "혼자 5층까지 쌓음", True),
]

#: 수량 표현 전수 조사. 네 번 연속 같은 자리를 고친 뒤(2026-10-08 · 10-11 ·
#: 10-14 · 10-17 멘토 리뷰) 변형을 한 번에 모았다. **여기 없는 모양이 또 나오면
#: 그때 하나 더 고치지 말고, 이 목록을 먼저 늘린 뒤에 고친다.**
#:
#: (갈래, 요약 문장, 인용, 통과해야 하나) · 묶음 날짜는 2026-10-07
COUNT_CASES = [
    # ── 표기 ──
    ("표기", "공을 7번 주고받았다", "공을 주고받았다", False),
    ("표기", "공을 일곱 번 주고받았다", "공을 주고받았다", False),
    ("표기", "공을 열두 번 주고받았다", "공을 두 번 주고받음", False),
    ("표기", "공을 마흔 번 주고받았다", "공을 주고받았다", False),
    ("표기", "공을 백 번 주고받았다", "공을 주고받았다", False),
    ("표기", "공을 열두번 주고받았다", "공을 주고받았다", False),
    # ── 값을 정할 수 없는 수량 ──
    ("모호", "공을 여러 번 주고받았다", "공을 주고받았다", False),
    ("모호", "공을 몇 번 주고받았다", "공을 주고받았다", False),
    ("모호", "공을 두세 번 주고받았다", "공을 주고받았다", False),
    ("모호", "공을 서너 번 주고받았다", "공을 주고받았다", False),
    ("모호", "공을 수차례 주고받았다", "공을 주고받았다", False),
    ("모호", "공을 2~3번 주고받았다", "공을 주고받았다", False),
    # 원문이 모호한데 요약이 구체적이면 그게 환각이다
    ("모호", "공을 세 번 주고받았다", "여러 번 주고받음", False),
    # ── 서수 ──
    ("서수", "세 번째 시도에서 성공했다", "시도에서 성공함", False),
    ("서수", "첫 번째 시도에서 성공했다", "시도에서 성공함", False),
    # ── 조사·어미가 붙어도 ──
    ("조사", "공을 세 번을 주고받았다", "공을 주고받았다", False),
    ("조사", "공을 세 번씩 주고받았다", "공을 주고받았다", False),
    # ── 횟수 밖의 단위 ──
    ("단위", "블록 5개를 쌓았다", "블록을 쌓았다", False),
    ("단위", "친구 3명과 놀았다", "친구와 놀았다", False),
    ("단위", "20분 동안 앉아 있었다", "앉아 있었다", False),
    ("단위", "다섯 층까지 쌓았다", "쌓았다", False),
    ("단위", "두 바퀴 돌았다", "돌았다", False),
    ("단위", "세 걸음 걸었다", "걸었다", False),
    ("단위", "공을 7번 주고받았다", "7층 놀이방에서 놀았다", False),
    # ── 수량이 아닌 것을 수량으로 읽지 않는다 ──
    ("오탐", "이번 주에는 잘 참여했다", "잘 참여함", True),
    ("오탐", "지난번보다 오래 앉아 있었다", "오래 앉아 있음", True),
    ("오탐", "매번 같은 자리에 앉았다", "같은 자리에 앉음", True),
    ("오탐", "한 번도 울지 않았다", "울지 않음", True),
    ("오탐", "세 번도 못 했다", "하지 못함", True),
    ("오탐", "한편으로는 차분했다", "차분함", True),
    # "도" 가 단위 뒤가 아니면 수량은 그대로 센다
    ("오탐", "오늘도 블록을 2번 쌓았다", "블록을 쌓았다", False),
    ("오탐", "오늘도 블록을 두 번 쌓았다", "블록을 쌓았다", False),
    # 단위 뒤의 "도" 라도 긍정이면 횟수 주장이다
    ("오탐", "세 번도 넘게 시도했다", "시도함", False),
    ("오탐", "세 번도 넘게 시도했다", "세 번 시도함", True),
    # ── 근거가 있으면 통과 ──
    ("정상", "공을 7번 주고받았다", "공을 7번 주고받음", True),
    ("정상", "공을 세 번 주고받았다", "공을 3번 주고받음", True),
    ("정상", "세 차례 주고받았다", "세 번 주고받음", True),
    ("정상", "열두 번 주고받았다", "12번 주고받음", True),
    ("정상", "다섯 층까지 쌓았다", "혼자 다섯 층까지 쌓음", True),
    ("정상", "여러 번 여닫았다", "창문을 여러 번 여닫음", True),
    ("정상", "10월 7일 체육에 참여했다", "체육 참여", True),
    # ── 근사·복합 수사 (값을 정할 수 없으므로 토큰 그대로 대조) ──
    ("근사", "한두 번 정도 반복했다", "반복했다", False),
    ("근사", "한두번 반복했다", "반복했다", False),
    ("근사", "두어 번 반복했다", "반복했다", False),
    ("근사", "서넛 번 했다", "했다", False),
    ("근사", "네다섯 번 했다", "했다", False),
    ("근사", "대여섯 번 했다", "했다", False),
    ("근사", "예닐곱 번 했다", "했다", False),
    ("근사", "몇몇 번 했다", "했다", False),
    ("근사", "수십 번 했다", "했다", False),
    ("근사", "수백 번 했다", "했다", False),
    # 원문이 "한두 번" 인데 요약이 "두 번" 이면 환각이다
    ("근사", "두 번 반복했다", "한두 번 반복함", False),
    ("근사", "한두 번 반복했다", "한두 번 반복함", True),
    # ── "여" 가 붙는 근사 · 큰 수 · 관형형 ──
    ("근사", "백여 번 반복했다", "반복했다", False),
    ("근사", "십여 번 반복했다", "반복했다", False),
    ("근사", "수십여 번 반복했다", "반복했다", False),
    ("근사", "이십여 번 반복했다", "반복했다", False),
    ("근사", "열댓 번 했다", "했다", False),
    ("근사", "두어세 번 했다", "했다", False),
    ("근사", "마흔두 번 했다", "했다", False),
    ("근사", "스무 번 했다", "했다", False),
    ("근사", "스물한 번 했다", "했다", False),
    # "여" 로 끝나는 보통 낱말을 수량으로 읽지 않는다
    ("근사", "활동에 참여했다", "활동 참여함", True),
    # ── 조사·꼬리말이 붙어도 ──
    ("조사", "두 번가량 반복했다", "반복했다", False),
    ("조사", "두 번쯤 반복했다", "반복했다", False),
    ("조사", "두 번까지 반복했다", "반복했다", False),
    # ── 단위가 일반 명사와 겹칠 때 ──
    # 아는 수사일 때만 센다. 안 그러면 "점심 시간" 이 수량이 된다
    ("명사", "아이 장난감을 정리했다", "장난감 정리함", True),
    ("명사", "점심 시간에 밥을 먹었다", "밥을 먹음", True),
    ("명사", "치료 시간이 끝났다", "시간이 끝남", True),
    ("명사", "마지막 장면에서 울었다", "장면에서 울음", True),
    ("명사", "교실 안에서 놀았다", "교실에서 놀았다", True),
    ("명사", "세 시간 동안 앉아 있었다", "앉아 있었다", False),
    ("명사", "그림책 두 권을 봤다", "그림책을 봄", True),
    # ── 일부러 안 잡는 것 (한자어 수사) ──
    # "이 회기에는" 의 "이" 를 2 로 읽어 정상 문장을 버린다. 놓치는 쪽을 택했다.
    ("제외", "공을 칠 회 주고받았다", "공을 주고받았다", True),
]

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

    print("\n■ 날짜 숫자는 자리만 면제한다 (묶음 날짜 2026-10-07)")
    for name, text, quote, want_pass in DATE_CASES:
        left = _numbers_outside_dates(text, "2026-10-07") - _numbers(quote)
        got_pass = not left
        ok = got_pass == want_pass
        print(f"  {'✓' if ok else '✗'} {'통과' if got_pass else '버림'}  {name}")
        print(f"      {text!r} · 인용 {quote!r} · 근거 없는 숫자 {left or '{}'}")
        if not ok:
            failed += 1

    print("\n■ 수량 표현 전수 조사 (묶음 날짜 2026-10-07)")
    group = None
    for cat, text, quote, want_pass in COUNT_CASES:
        left = _numbers_outside_dates(text, "2026-10-07") - _numbers(quote)
        got_pass = not left and not (_counts(text) - _counts(quote))
        ok = got_pass == want_pass
        if cat != group:
            print(f"    [{cat}]")
            group = cat
        print(f"  {'✓' if ok else '✗'} {'통과' if got_pass else '버림'}  {text!r} · 인용 {quote!r}")
        if not ok:
            print(f"      → {'버려야' if not want_pass else '통과해야'} 함")
            failed += 1

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
