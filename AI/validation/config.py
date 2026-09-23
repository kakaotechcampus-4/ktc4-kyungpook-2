# AI/validation/config.py 맨 위에 추가
LUNA_CHAT_PATH = "/v1/chat/completions"
LUNA_MODEL = "gpt-5.6-luna"
LUNA_TIMEOUT = 60.0

# 기존 내용은 그대로 유지
ISSUE_LEVEL = {
    "진단명": "BLOCK",
    "개인정보표현": "BLOCK",
    "확정적표현": "REVIEW",
    "다수아동언급": "REVIEW",
    "추측성표현": "REVIEW",
    "감정적표현": "REVIEW",
    "위험행동표현": "REVIEW",
}

STRUCTURAL_PII_PATTERNS = [
    r"01[0-9]-\d{3,4}-\d{4}",
    r"\d{6}-[1-4]\d{6}",
]