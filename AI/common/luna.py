import os

CHAT_PATH = "/v1/chat/completions"

DEFAULT_MODEL = "gpt-6-luna"


AGENTS = ("matching", "validation", "summary", "insight")


def setting(agent: str, name: str) -> str | None:
    return os.environ.get(f"{agent.upper()}_{name}") or None


def endpoint(agent: str) -> tuple[str, str] | None:
    url, key = setting(agent, "API_URL"), os.environ.get("API_KEY")
    if not (url and key):
        return None
    if not url.rstrip("/").endswith(CHAT_PATH):
        url = url.rstrip("/") + CHAT_PATH
    return url, key


def not_configured(agent: str) -> str:
    return f"{agent.upper()}_API_URL / API_KEY 가 설정되지 않았습니다"


def model(agent: str) -> str:
    return setting(agent, "MODEL") or DEFAULT_MODEL


def json_format(agent: str) -> dict:
    if (setting(agent, "JSON_MODE") or "on").strip().lower() in ("off", "0", "false", "no"):
        return {}
    return {"response_format": {"type": "json_object"}}
