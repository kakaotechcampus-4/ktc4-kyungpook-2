# AI/common/luna.py
"""
Luna 접속 설정. 네 에이전트가 같은 값을 쓴다.

각자 config.py 에 복사해 두면 모델을 바꿀 때 네 군데를 고쳐야 하고, 한 군데를
빠뜨리면 **에이전트마다 다른 모델로 측정된다.** 실제로 그 상태였다 —
insight 만 환경변수를 읽고 나머지 셋은 하드코딩이라, 같은 명령을 돌려도
사람마다 다른 숫자가 나왔다 (2026-10-07 #119 리뷰).

호출 제한시간(LUNA_TIMEOUT)은 공유하지 않는다. 요약은 입력이 일지 여러 건이라
다른 셋보다 길다.
"""

import os

#: LUNA_API_URL 은 엔드포인트 base 만 담는다. 실제 경로는 여기서 붙인다.
CHAT_PATH = "/v1/chat/completions"

#: .env 에 LUNA_MODEL 이 없을 때 쓰는 값.
#:
#: 2026-10-09 에 gpt-5.6-luna → gpt-6-luna 로 바꿨다. 키마다 허용 모델이 정해져
#: 있고, 키가 바뀌면서 5.6 쪽이 400 이 됐다. 되돌릴 수 없으므로 두 모델을 나란히
#: 비교할 방법은 없다.
DEFAULT_MODEL = "gpt-6-luna"


def model() -> str:
    """
    쓸 모델 이름. **부를 때마다 읽는다.**

    모듈을 읽는 시점에 os.environ 을 보면 안 된다 — main.py 가 import 를 모두
    마친 뒤에 load_dotenv() 를 부르기 때문에, 그때 읽으면 .env 를 못 본다.
    평가 스크립트는 반대 순서라 됐고 서버만 안 됐다.
    """
    return os.environ.get("LUNA_MODEL") or DEFAULT_MODEL
