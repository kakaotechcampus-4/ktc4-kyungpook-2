import os

import requests
from dotenv import load_dotenv
from fastapi import FastAPI

from matching.graph import run_matching
from matching.schemas import MatchingInput, MatchingOutput

load_dotenv()

app = FastAPI(title="ITDA AI")

# 모듈을 읽는 시점에 키가 없다고 죽지 않게 한다.
# 매칭 그래프는 Luna 없이도 돌아야 트랙 A 가 키 없이 채점 스크립트를 붙일 수 있다.
LUNA_API_URL = os.environ.get("LUNA_API_URL")
LUNA_API_KEY = os.environ.get("LUNA_API_KEY")


@app.get("/health")
def health():
    return {"status": "ok", "luna_configured": bool(LUNA_API_URL and LUNA_API_KEY)}


@app.post("/matching", response_model=MatchingOutput)
def matching(payload: MatchingInput) -> MatchingOutput:
    """일지 항목 한 건이 어느 아이의 것인지 판정한다."""
    return run_matching(payload)


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
