from unittest.mock import Mock

from fastapi.testclient import TestClient
import pytest
import requests

import main
from matching import nodes
from matching.llm import LlmError, LlmResult, ask_json

CLIENT = TestClient(main.app)


def payload(content="송준호가 블록을 쌓았다."):
    return {"journal_entry_id": 1041, "content": content,
            "roster": [{"child_id": 8, "name": "송준호", "birthdate": "2019-11-26"}]}


@pytest.mark.parametrize("configured", [False, True])
def test_health_configuration_without_calling_model(monkeypatch, configured):
    monkeypatch.setattr(main, "LUNA_API_URL", "http://luna.invalid" if configured else None)
    monkeypatch.setattr(main, "LUNA_API_KEY", "test-only" if configured else None)
    response = CLIENT.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "luna_configured": configured}


def test_invalid_matching_input_returns_422():
    assert CLIENT.post("/matching", json={"content": "incomplete"}).status_code == 422


def test_exact_name_matching_skips_model(monkeypatch):
    model = Mock(side_effect=AssertionError("exact matching must not call the model"))
    monkeypatch.setattr(nodes, "ask_json", model)
    response = CLIENT.post("/matching", json=payload())
    assert response.status_code == 200
    assert response.json()["status"] == "auto"
    assert response.json()["matched_child_id"] == 8
    assert response.json()["llm_called"] is False
    model.assert_not_called()


def test_ambiguous_matching_uses_mocked_model(monkeypatch):
    model = Mock(return_value=LlmResult({"child_id": None, "confidence": 0, "quotes": []}))
    monkeypatch.setattr(nodes, "ask_json", model)
    response = CLIENT.post("/matching", json=payload("이 아이가 블록을 쌓았다."))
    assert response.status_code == 200
    assert response.json()["status"] == "unmatched"
    model.assert_called_once()


def test_model_failure_does_not_break_matching_endpoint(monkeypatch):
    monkeypatch.setattr(nodes, "ask_json", Mock(side_effect=LlmError("mock failure")))
    response = CLIENT.post("/matching", json=payload("이 아이가 블록을 쌓았다."))
    assert response.status_code == 200
    assert response.json()["status"] in {"review", "unmatched"}
    assert response.json()["matched_child_id"] is None


def test_missing_key_is_reported_without_network():
    with pytest.raises(LlmError, match="설정되지"):
        ask_json([])


def test_llm_response_parsing_uses_mock_http(monkeypatch):
    monkeypatch.setenv("LUNA_API_URL", "http://luna.invalid")
    monkeypatch.setenv("LUNA_API_KEY", "test-only")
    response = Mock()
    response.json.return_value = {"choices": [{"message": {"content": '```json\n{"child_id": 8}\n```'}}],
                                  "usage": {"total_tokens": 10}}
    post = Mock(return_value=response)
    monkeypatch.setattr(requests, "post", post)
    result = ask_json([])
    assert result.data == {"child_id": 8}
    assert result.usage == {"total_tokens": 10}
    assert post.call_args.args[0].endswith("/v1/chat/completions")


def test_llm_timeout_is_wrapped(monkeypatch):
    monkeypatch.setenv("LUNA_API_URL", "http://luna.invalid")
    monkeypatch.setenv("LUNA_API_KEY", "test-only")
    monkeypatch.setattr(requests, "post", Mock(side_effect=requests.Timeout("mock timeout")))
    with pytest.raises(LlmError, match="호출 실패"):
        ask_json([])
