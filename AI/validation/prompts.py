"""
Validation Agent 프롬프트를 한 곳에 모은다.

ISSUE_DEFINITIONS 는 evals/scripts/validation/README.md 의
"판정 기준 > 유형 정의" 표와 글자 하나까지 같아야 한다.
기준을 바꿀 때는 README 를 먼저 고치고, 여기에 같은 문장을 옮긴다.
evals/scripts/validation/test_prompt_sync.py 가 둘이 같은지 검사한다.

(감정적표현 판정 누락은 정의가 사람 머릿속에만 있고 프롬프트에 없어서
생겼다. 정의를 문서·코드·테스트로 묶어두는 이유다.)
"""
from .config import ISSUE_LEVEL

# ── 유형 정의 (README 표와 1:1) ───────────────────────────────────
#: 순서는 README 표 순서와 같다. 프롬프트에도 이 순서로 나간다.
ISSUE_DEFINITIONS = {
    "진단명": '진단명, 장애 등급, 중증도, 소견서·검사 결과, 치료, 복용 약 등 의료기관에서 나온 의료 정보. 진단명이 직접 쓰이지 않아도 해당한다 ("소견서상 중증"). 수면·식사 같은 일상 관찰은 해당하지 않는다',
    "개인정보표현": '연락처, 주소, 생년월일 등 사람을 식별할 수 있는 정보. 연도 없는 생일("생일(11월 21일)")도 포함한다',
    "확정적표현": '진단은 아니지만 아이의 미래나 성향을 단정하는 서술 ("절대 바뀌지 않을 것")',
    "다수아동언급": '이름이나 가족 호칭(형·누나·동생 등)으로 특정되는 다른 아이가 한 명이라도 나온다. 이름 없는 "친구", "짝꿍", "옆자리 친구", "다른 아이들"은 해당하지 않는다',
    "추측성표현": '근거 없이 원인이나 사정을 추측한다 ("아마 집에서 무슨 일이", "짐작건대")',
    "감정적표현": '관찰이 아니라 작성자의 주관적 감정이 드러난다. 지나친 애정 표현, 힘들다는 하소연을 포함한다. 가벼운 칭찬이나 긍정적 평가("대견했다", "예쁜 마음을 표현함")는 해당하지 않는다',
    "위험행동표현": '자해, 타해(밀치기·때리기 포함), 무단 이탈, 추락 위험처럼 사람이 다치거나 다칠 수 있는 행동의 기록, 또는 그런 행동을 필요 이상으로 상세하게 묘사한 것. 물건에만 하는 행동(물건을 밀치거나 뒤엎기)과 도움 없이 해낸 일상 동작(혼자 계단 오르내리기)은 해당하지 않는다. 의도 없이 부딪힌 접촉, 물건을 두고 다투거나 빼앗으려 한 행동, 주인공이 밀리거나 맞은 쪽인 경우, 실제 행동 없이 경향이나 가능성만 적은 서술("자해 경향이 있음")도 해당하지 않는다',
}

# ── 정의 밖의 지시 ────────────────────────────────────────────────
#: 귀속 예외 유형(config.ATTRIBUTION_EXEMPT)은 누구 얘기든 코드가 반영한다.
#: 그런데 모델이 애초에 목록에 안 올리면 코드가 받을 게 없으므로,
#: 다른 사람 얘기라도 일단 다 적게 한다.
LIST_ALL_RULE = "판정 대상 아이가 아닌 다른 사람(가족, 다른 아이)에 대한 내용이어도 해당하면 빠짐없이 나열하세요."

OUTPUT_FORMAT = (
    'JSON 형식으로만 답하세요:\n'
    '{"issues": [{"issue_type": "진단명", "attributed_to_subject": true, "evidence_quote": "원문 인용"}]}'
)


def _definition_block() -> str:
    """ISSUE_LEVEL 등급별로 정의를 묶는다 (BLOCK 먼저, REVIEW 다음)."""
    lines = []
    for level in ("BLOCK", "REVIEW"):
        lines.append(f"{level}:")
        for issue, definition in ISSUE_DEFINITIONS.items():
            if ISSUE_LEVEL[issue] == level:
                lines.append(f"- {issue}: {definition}")
        lines.append("")
    return "\n".join(lines).rstrip()


def subject_line(subject_name: str | None) -> str:
    """대상을 알면 이름을 명시하고, 모르면 모델한테도 "모른다"고 알려서
    attributed_to_subject 를 스스로 false 로 두게 유도한다 (2차 방어선,
    1차는 nodes.reflect 의 subject_known 검사)."""
    if subject_name:
        return (f'지금 판정 대상 아동은 "{subject_name}"입니다. 각 유형이 있다면, '
                f'그것이 {subject_name} 본인에 대한 서술인지 판단하세요.')
    return ('이번 요청에는 판정 대상 아동 정보가 제공되지 않았습니다. '
            '이 경우 attributed_to_subject는 항상 false로 표시하세요.')


def build_messages(content: str, subject_name: str | None) -> list[dict]:
    prompt = (
        f"다음 관찰 기록에서 아래 {len(ISSUE_DEFINITIONS)}개 유형 중 해당하는 것이 있는지 판단하세요.\n\n"
        f"{_definition_block()}\n\n"
        f"{LIST_ALL_RULE}\n\n"
        f"{subject_line(subject_name)}\n\n"
        f"기록: {content}\n\n"
        f"{OUTPUT_FORMAT}"
    )
    return [{"role": "user", "content": prompt}]
