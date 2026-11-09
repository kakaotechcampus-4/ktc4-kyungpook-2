from dotenv import load_dotenv

load_dotenv()

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from common import metrics, progress
from common.errors import LlmUnavailable
from insight.graph import run_insight
from insight.schemas import InsightInput, InsightOutput
from notify.graph import run_notify
from notify.schemas import NotifyInput, NotifyOutput
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
    failure = getattr(exc, "failure", "unknown")
    print(f"[{request.url.path.strip('/') or 'unknown'}] LLM 호출 실패 ({failure}): {exc}")
    detail = {"reason": exc.reason, "message": exc.message}
    if failure != "unknown":
        detail["failure"] = failure
    return JSONResponse(status_code=503, content={"detail": detail})


@app.get("/health")
def health() -> JSONResponse:
    configured = is_configured()
    return JSONResponse(
        status_code=200 if configured else 503,
        content={"status": "ok" if configured else "unavailable", "luna_configured": configured},
    )


@app.get("/metrics")
def read_metrics() -> dict:
    return metrics.snapshot()


@app.get("/progress")
async def read_progress() -> list[dict]:
    try:
        return progress.snapshot()
    except Exception as exc:
        print(f"[progress] 조회 실패: {exc!r}")
        return []


@app.post("/matching", response_model=MatchingOutput)
def matching(payload: MatchingInput) -> MatchingOutput:
    with metrics.track("/matching", metrics.key_of(payload.journal_entry_id)), \
            progress.begin("matching", journal_entry_id=payload.journal_entry_id):
        return run_matching(payload)


@app.post("/validation", response_model=ValidationOutput)
def validation(payload: ValidationInput) -> ValidationOutput:
    with metrics.track("/validation", metrics.key_of(payload.journal_entry_id)), \
            progress.begin("validation", journal_entry_id=payload.journal_entry_id):
        return run_validation(payload)


@app.post("/summary", response_model=SummaryOutput)
def summary(payload: SummaryInput) -> SummaryOutput:
    key = metrics.key_of(payload.child_id, payload.entry_date, payload.institution_id)
    with metrics.track("/summary", key), \
            progress.begin("summary", child_id=payload.child_id, entry_date=payload.entry_date,
                           institution_id=payload.institution_id):
        return run_summary(payload)


@app.post("/insight", response_model=InsightOutput)
def insight(payload: InsightInput) -> InsightOutput:
    key = metrics.key_of(payload.child_id, payload.period_from, payload.period_to)
    with metrics.track("/insight", key), \
            progress.begin("insight", child_id=payload.child_id, period_from=payload.period_from,
                           period_to=payload.period_to):
        return run_insight(payload)


@app.post("/notify", response_model=NotifyOutput)
def notify(payload: NotifyInput) -> NotifyOutput:
    key = metrics.key_of(payload.child_id, payload.notification_id)
    with metrics.track("/notify", key):
        return run_notify(payload)
