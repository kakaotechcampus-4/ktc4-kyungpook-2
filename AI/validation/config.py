from common.luna import CHAT_PATH

ISSUE_LEVEL = {
    "진단명": "BLOCK",
    "개인정보표현": "BLOCK",
    "확정적표현": "REVIEW",
    "다수아동언급": "REVIEW",
    "추측성표현": "REVIEW",
    "감정적표현": "REVIEW",
    "위험행동표현": "REVIEW",
    "건강관리기록": "REVIEW",
}

ATTRIBUTION_EXEMPT = {"진단명", "개인정보표현", "다수아동언급", "감정적표현", "추측성표현", "건강관리기록"}

STRUCTURAL_PII_PATTERNS = [
    r"01[0-9]-\d{3,4}-\d{4}",
    r"\d{6}-[1-4]\d{6}",
]


LUNA_CHAT_PATH = CHAT_PATH

LUNA_TIMEOUT = 60.0