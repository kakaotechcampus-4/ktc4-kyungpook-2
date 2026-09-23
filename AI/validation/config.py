# AI/validation/config.py

# 판정 최종 등급: 유형별로 REVIEW/BLOCK 중 어디로 갈지
ISSUE_LEVEL = {
    "진단명": "BLOCK",
    "개인정보표현": "BLOCK",
    "확정적표현": "REVIEW",
    "다수아동언급": "REVIEW",
    "추측성표현": "REVIEW",
    "감정적표현": "REVIEW",
    "위험행동표현": "REVIEW",
}

# 정규식으로 바로 확인 가능한 개인정보 패턴.
# 이건 "귀속 검증" 없이 항상 BLOCK — 전화번호·주민번호 형식이 문서에
# 그대로 있으면, 누구 얘기든 이미 그 자체로 위험하기 때문 (Matching과 다른 지점)
STRUCTURAL_PII_PATTERNS = [
    r"01[0-9]-\d{3,4}-\d{4}",   # 휴대폰 번호
    r"\d{6}-[1-4]\d{6}",         # 주민등록번호 형식
]