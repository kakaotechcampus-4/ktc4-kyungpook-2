import os
import requests
from fastapi import FastAPI
from dotenv import load_dotenv
load_dotenv()


app = FastAPI()

LUNA_API_URL = os.environ["LUNA_API_URL"]
LUNA_API_KEY = os.environ["LUNA_API_KEY"]


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/hello")
def hello():
    return {"message": "hello from itda agent"}


@app.get("/llm-test")
def llm_test():
    payload = {
        "messages": [
            {"role": "user", "content": "안녕하세요, 한 문장으로 자기소개 해주세요."}
        ]
    }
    headers = {
        "accept": "application/json",
        "content-type": "application/json",
        "Authorization": f"Bearer {LUNA_API_KEY}",
    }
    response = requests.post(LUNA_API_URL, json=payload, headers=headers, timeout=30)
    response.raise_for_status()
    return response.json()