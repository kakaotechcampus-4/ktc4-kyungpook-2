class LlmUnavailable(RuntimeError):

    reason = "llm_unavailable"

    message = "Luna 호출 실패"

    def __init__(self, *args, failure: str = "unknown"):
        super().__init__(*args)
        self.failure = failure


FAILURE_KINDS = ("timeout", "empty_response", "bad_json", "bad_response", "http_error", "not_configured")
