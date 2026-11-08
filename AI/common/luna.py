# AI/common/luna.py
"""
LLM 접속 설정. 에이전트마다 주소·모델·JSON 강제를 따로 두고, 키(API_KEY)는 같이 쓴다.

    .env:  API_KEY · {MATCHING,VALIDATION,SUMMARY,INSIGHT}_API_URL · _MODEL · _JSON_MODE

모델 비교(2026-11-09, evals/scripts/real/MODEL_COMPARE.md) 뒤로 요약·인사이트는 sonnet,
매칭·검증은 루나를 쓴다. 매칭·검증의 안전 지표는 루나로 쟀기 때문이다.
아래는 한 곳에서 읽어야 하는 이유(예전 기록)다.

각자 config.py 에 복사해 두면 모델을 바꿀 때 네 군데를 고쳐야 하고, 한 군데를
빠뜨리면 **에이전트마다 다른 모델로 측정된다.** 실제로 그 상태였다 —
insight 만 환경변수를 읽고 나머지 셋은 하드코딩이라, 같은 명령을 돌려도
사람마다 다른 숫자가 나왔다 (2026-10-07 #119 리뷰).

호출 제한시간(LUNA_TIMEOUT)은 공유하지 않는다. 요약은 입력이 일지 여러 건이라
다른 셋보다 길다.
"""

import os

#: <에이전트>_API_URL 은 엔드포인트 base 만 담는다. 실제 경로는 여기서 붙인다.
CHAT_PATH = "/v1/chat/completions"

#: .env 에 <에이전트>_MODEL 이 없을 때 쓰는 값.
#:
#: 2026-10-09 에 gpt-5.6-luna → gpt-6-luna 로 바꿨다. 키마다 허용 모델이 정해져
#: 있고, 키가 바뀌면서 5.6 쪽이 400 이 됐다. 되돌릴 수 없으므로 두 모델을 나란히
#: 비교할 방법은 없다.
DEFAULT_MODEL = "gpt-6-luna"


AGENTS = ("matching", "validation", "summary", "insight")


def setting(agent: str, name: str) -> str | None:
    """에이전트 하나의 환경변수. setting("summary", "MODEL") → SUMMARY_MODEL. **부를 때마다 읽는다.**"""
    return os.environ.get(f"{agent.upper()}_{name}") or None


def endpoint(agent: str) -> tuple[str, str] | None:
    """
    (chat 주소, 키). 주소나 키가 없으면 None — 에러는 에이전트마다 종류가 달라 부른 쪽에서 낸다.

    .env 에는 base 만 둔다. 경로가 이미 붙어 있으면 그대로 쓴다.
    """
    url, key = setting(agent, "API_URL"), os.environ.get("API_KEY")
    if not (url and key):
        return None
    if not url.rstrip("/").endswith(CHAT_PATH):
        url = url.rstrip("/") + CHAT_PATH
    return url, key


def not_configured(agent: str) -> str:
    return f"{agent.upper()}_API_URL / API_KEY 가 설정되지 않았습니다"


def model(agent: str) -> str:
    """
    쓸 모델 이름. **부를 때마다 읽는다.**

    모듈을 읽는 시점에 os.environ 을 보면 안 된다 — main.py 가 import 를 모두
    마친 뒤에 load_dotenv() 를 부르기 때문에, 그때 읽으면 .env 를 못 본다.
    평가 스크립트는 반대 순서라 됐고 서버만 안 됐다.
    """
    return setting(agent, "MODEL") or DEFAULT_MODEL


def json_format(agent: str) -> dict:
    """
    요청 본문에 넣을 JSON 강제 설정. **부를 때마다 읽는다** (model() 과 같은 이유).

    기본은 response_format=json_object 로 JSON 출력을 강제한다. 루나는 이 값 그대로다.
    모델이 response_format 을 받지 않으면 <에이전트>_JSON_MODE=off 로 끈다. 그때는 강제 없이
    프롬프트의 형식 지시와 응답에서 JSON 만 골라내는 파서(_parse_json_block)에 맡긴다.

    예: claude 계열(opus·sonnet)은 엘리스 프록시에서 "response_format is not supported" 로 400 을 낸다 (2026-11-08).
    """
    if (setting(agent, "JSON_MODE") or "on").strip().lower() in ("off", "0", "false", "no"):
        return {}
    return {"response_format": {"type": "json_object"}}
