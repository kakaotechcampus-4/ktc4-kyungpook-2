#!/usr/bin/env python3
"""Check a running AI server without calling the external model."""

import argparse
import json
import sys
import urllib.error
import urllib.request

# 이름이 본문에 그대로 있으면 매칭 그래프는 모델을 부르지 않는다.
MATCHING_SAMPLE = {
    "journal_entry_id": 1,
    "content": "송준호가 블록을 높이 쌓았다.",
    "roster": [
        {"child_id": 1, "name": "송준호", "birthdate": "2019-11-26"},
        {"child_id": 2, "name": "박서연", "birthdate": "2019-05-05"},
    ],
}
# 검증은 항상 모델을 부른다. 키가 없는 CI에서만 쓴다.
# 전화번호 형식은 모델 결과와 무관하게 200 + BLOCK이고, 그 밖의 본문은 모델을 못 써서 503이 된다.
VALIDATION_SAMPLES = [
    ({"journal_entry_id": 2, "content": "송준호 보호자 연락처 010-0000-0000.",
      "subject_child_id": 1, "subject_name": "송준호"}, 200, "BLOCK"),
    ({"journal_entry_id": 3, "content": "송준호가 블록을 높이 쌓았다.",
      "subject_child_id": 1, "subject_name": "송준호"}, 503, None),
]


def call(url, payload=None):
    """(HTTP 상태, JSON 본문). 4xx/5xx 도 본문을 읽어 돌려준다. 연결 실패는 그대로 예외."""
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"content-type": "application/json"} if data else {}
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def verify(base, *, offline=False, require_luna=False):
    status, health = call(base + "/health")
    luna = health.get("luna_configured") is True
    # Luna 설정이 있으면 200 + ok, 없으면 503 + unavailable
    if (status, health.get("status")) != ((200, "ok") if luna else (503, "unavailable")):
        raise AssertionError(f"unexpected /health response: {status} " + json.dumps(health))
    if require_luna and not luna:
        raise AssertionError("Luna is not configured; check AI/.env on the server")
    if offline and luna:
        raise AssertionError("offline check expects no Luna configuration")

    _, result = call(base + "/matching", MATCHING_SAMPLE)
    expected = {"status": "auto", "matched_child_id": 1, "llm_called": False}
    actual = {key: result.get(key) for key in expected}
    if actual != expected:
        raise AssertionError("unexpected /matching response: " + json.dumps(actual, ensure_ascii=False))

    if offline:
        for sample, expected_status, verdict in VALIDATION_SAMPLES:
            status, result = call(base + "/validation", sample)
            if status != expected_status or (verdict and result.get("verdict") != verdict):
                raise AssertionError(f"unexpected /validation response: {status} " + json.dumps(result, ensure_ascii=False))
    return luna


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8000")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--offline", action="store_true", help="CI: no Luna key, also check /validation fallbacks")
    mode.add_argument("--require-luna", action="store_true", help="production: Luna must be configured")
    args = parser.parse_args()
    try:
        luna = verify(args.url.rstrip("/"), offline=args.offline, require_luna=args.require_luna)
    except Exception as error:
        print("AI check failed: " + str(error), file=sys.stderr)
        sys.exit(1)
    print("AI healthy (Luna configured: " + ("yes" if luna else "no") + ")")


if __name__ == "__main__":
    main()
