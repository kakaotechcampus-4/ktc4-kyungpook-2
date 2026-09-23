# AI/validation/config.py 맨 위에 추가
LUNA_CHAT_PATH = "..."   # matching/config.py에서 복사
LUNA_MODEL = "..."       # matching/config.py에서 복사
LUNA_TIMEOUT = ...       # matching/config.py에서 복사

# 기존 내용
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