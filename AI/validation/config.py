# AI/validation/config.py

# confidence 임계값은 안 씀 (Matching에서 배운 교훈)
# 판정은 규칙 기반: 근거가 판정 대상에 명확히 귀속되는지 여부

ISSUE_TYPES = {
    "BLOCK": ["진단명", "개인정보표현"],
    "REVIEW": ["확정적표현", "다수아동언급", "추측성표현", "감정적표현", "위험행동표현"],
}