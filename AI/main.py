import os

import requests
from dotenv import load_dotenv
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from common.errors import LlmUnavailable
from matching.graph import run_matching
from matching.schemas import MatchingInput, MatchingOutput
from summary.graph import run_summary
from summary.schemas import SummaryInput, SummaryOutput
from validation.graph import run_validation
from validation.llm import is_configured
from validation.schemas import ValidationInput, ValidationOutput

load_dotenv()

app = FastAPI(title="ITDA AI")

# 모듈을 읽는 시점에 키가 없다고 죽지 않게 한다.
# 매칭 그래프는 Luna 없이도 돌아야 트랙 A 가 키 없이 채점 스크립트를 붙일 수 있다.
LUNA_API_URL = os.environ.get("LUNA_API_URL")
LUNA_API_KEY = os.environ.get("LUNA_API_KEY")


@app.exception_handler(LlmUnavailable)
def llm_unavailable(request: Request, exc: LlmUnavailable) -> JSONResponse:
    """
    LLM 을 못 써서 결과를 내지 못했을 때. 검증·요약이 같이 쓴다.

    BE 는 5xx 면 재시도하고, 그래도 실패하면 FAILED 로 둔다.
    실패 원인에는 Luna 엔드포인트 주소가 들어 있어서 응답에는 넣지 않고
    서버 로그에만 남긴다. 어느 에이전트였는지는 경로로 구분한다.

    에이전트마다 예외를 따로 두면 안 된다 — main.py 에서 이름이 덮여
    핸들러가 한쪽에만 걸리고 나머지는 500 으로 나간다. common/errors.py 참고.
    """
    print(f"[{request.url.path.strip('/') or 'unknown'}] LLM 호출 실패: {exc}")
    return JSONResponse(
        status_code=503,
        content={"detail": {"reason": "llm_unavailable", "message": "Luna 호출 실패"}},
    )


@app.get("/health")
def health() -> JSONResponse:
    """Luna 설정이 없으면 503. BE 는 이걸 보고 일지를 보내지 않는다. (설정만 보고 실제 호출은 안 한다)"""
    configured = is_configured()
    return JSONResponse(
        status_code=200 if configured else 503,
        content={"status": "ok" if configured else "unavailable", "luna_configured": configured},
    )


@app.post("/matching", response_model=MatchingOutput)
def matching(payload: MatchingInput) -> MatchingOutput:
    """일지 항목 한 건이 어느 아이의 것인지 판정한다."""
    return run_matching(payload)

@app.post("/validation", response_model=ValidationOutput)
def validation(payload: ValidationInput) -> ValidationOutput:
    """일지 항목 한 건이 저장해도 안전한지 판정한다."""
    return run_validation(payload)


@app.post("/summary", response_model=SummaryOutput)
def summary(payload: SummaryInput) -> SummaryOutput:
    """같은 아이의 같은 날 일지 여러 건을 한 편의 요약으로 묶는다."""
    return run_summary(payload)


@app.get("/llm-test")
def llm_test():
    """Luna 응답 형태를 확인하기 위한 임시 엔드포인트."""
    if not (LUNA_API_URL and LUNA_API_KEY):
        return {"error": "LUNA_API_URL / LUNA_API_KEY 가 설정되지 않았습니다"}

    response = requests.post(
        LUNA_API_URL,
        json={"messages": [{"role": "user", "content": "안녕하세요, 한 문장으로 자기소개 해주세요."}]},
        headers={
            "accept": "application/json",
            "content-type": "application/json",
            "Authorization": f"Bearer {LUNA_API_KEY}",
        },
        timeout=30,
    )
    response.raise_for_status()
    return response.json()
