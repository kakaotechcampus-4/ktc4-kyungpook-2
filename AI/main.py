from dotenv import load_dotenv

# import 보다 먼저 부른다 — 모듈이 읽히는 동안 .env 가 보여야 한다.
load_dotenv()

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from common import metrics
from common.errors import LlmUnavailable
from insight.graph import run_insight
from insight.schemas import InsightInput, InsightOutput
from matching.graph import run_matching
from matching.schemas import MatchingInput, MatchingOutput
from summary.graph import run_summary
from summary.schemas import SummaryInput, SummaryOutput
from validation.graph import run_validation
from validation.llm import is_configured
from validation.schemas import ValidationInput, ValidationOutput

app = FastAPI(title="ITDA AI")

@app.exception_handler(LlmUnavailable)
def llm_unavailable(request: Request, exc: LlmUnavailable) -> JSONResponse:
    """
    LLM 을 못 써서 결과를 내지 못했을 때. 검증·요약·인사이트가 같이 쓴다.

    BE 는 5xx 면 재시도하고, 그래도 실패하면 FAILED 로 둔다.
    실패 원인에는 Luna 엔드포인트 주소가 들어 있어서 응답에는 넣지 않고
    서버 로그에만 남긴다. 어느 에이전트였는지는 경로로 구분한다.

    **무엇 때문인지는 detail.reason 으로 구분한다.** 상태 코드는 둘 다 503 이지만
    BE 의 후속 처리가 다르다 — llm_unavailable 은 차례를 멈추고 나중에 다시,
    no_grounded_claims 는 그 묶음만 되돌리고 다음으로 넘어간다 (#148 박찬진).
    값은 예외 클래스가 들고 있다 (common/errors.py).

    에이전트마다 예외를 따로 두면 안 된다 — main.py 에서 이름이 덮여
    핸들러가 한쪽에만 걸리고 나머지는 500 으로 나간다. common/errors.py 참고.
    """
    failure = getattr(exc, "failure", "unknown")
    print(f"[{request.url.path.strip('/') or 'unknown'}] LLM 호출 실패 ({failure}): {exc}")
    detail = {"reason": exc.reason, "message": exc.message}
    # 실패 종류는 덧붙이기만 한다. BE 는 reason 으로 처리를 가르고 모르는 칸은 무시한다.
    if failure != "unknown":
        detail["failure"] = failure
    return JSONResponse(status_code=503, content={"detail": detail})


@app.get("/health")
def health() -> JSONResponse:
    """Luna 설정이 없으면 503. BE 는 이걸 보고 일지를 보내지 않는다. (설정만 보고 실제 호출은 안 한다)"""
    configured = is_configured()
    return JSONResponse(
        status_code=200 if configured else 503,
        content={"status": "ok" if configured else "unavailable", "luna_configured": configured},
    )


@app.get("/metrics")
def read_metrics() -> dict:
    """
    실제로 몇 번 불렸는지. `repeats` 가 재시도가 몇 번 왔는지를 보여준다.

    BE 설정(`app.ai.retry-backoffs`)이 2개면 한 묶음의 `calls` 가 3 을 넘으면 안 된다.
    2026-10-10 E2E 에서 6 이 나왔다 (`#153`). 서버를 다시 띄우면 0 부터 센다.
    """
    return metrics.snapshot()


@app.post("/matching", response_model=MatchingOutput)
def matching(payload: MatchingInput) -> MatchingOutput:
    """일지 항목 한 건이 어느 아이의 것인지 판정한다."""
    with metrics.track("/matching", metrics.key_of(payload.journal_entry_id)):
        return run_matching(payload)


@app.post("/validation", response_model=ValidationOutput)
def validation(payload: ValidationInput) -> ValidationOutput:
    """일지 항목 한 건이 저장해도 안전한지 판정한다."""
    with metrics.track("/validation", metrics.key_of(payload.journal_entry_id)):
        return run_validation(payload)


@app.post("/summary", response_model=SummaryOutput)
def summary(payload: SummaryInput) -> SummaryOutput:
    """같은 아이의 같은 날 일지 여러 건을 한 편의 요약으로 묶는다."""
    # 요약은 일지 한 건이 아니라 묶음이라, 묶음 키로 센다 (아동 × 날짜 × 기관).
    key = metrics.key_of(payload.child_id, payload.entry_date, payload.institution_id)
    with metrics.track("/summary", key):
        return run_summary(payload)


@app.post("/insight", response_model=InsightOutput)
def insight(payload: InsightInput) -> InsightOutput:
    """한 아이의 승인된 요약들을 겹쳐 기관을 가로지르는 돌봄 패턴을 찾는다."""
    key = metrics.key_of(payload.child_id, payload.period_from, payload.period_to)
    with metrics.track("/insight", key):
        return run_insight(payload)
