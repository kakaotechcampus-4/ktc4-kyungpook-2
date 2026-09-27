import pytest
import requests


@pytest.fixture(autouse=True)
def prohibit_external_requests(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("CI tests must not call external services")
    monkeypatch.setattr(requests.sessions.Session, "request", forbidden)
    monkeypatch.delenv("LUNA_API_URL", raising=False)
    monkeypatch.delenv("LUNA_API_KEY", raising=False)
