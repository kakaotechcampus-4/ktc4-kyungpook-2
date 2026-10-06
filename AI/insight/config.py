# AI/insight/config.py
"""
인사이트 판정에 쓰는 상수를 한 곳에 모은다.

CRITERIA.md 가 원본이고 이 파일이 따라간다. 플래그 이름은 CRITERIA 표의
이름과 글자까지 같아야 한다 (test_criteria_sync.py 가 대조한다).
"""

# ── 성립 조건 (CRITERIA §4) — 코드가 거른다 ─────────────────────

#: 모델이 가리킨 claim_id 가 입력에 없으면 그 인사이트를 버린다.
#: 매칭의 "명부 밖 ID 는 버린다" 와 같은 원칙이다.
REQUIRE_VALID_CLAIM_IDS = True

#: 인사이트 하나에 필요한 최소 근거 claim 수. 관찰 하나는 패턴이 아니다.
MIN_EVIDENCE_CLAIMS = 2

#: 근거 수를 "서로 다른 날" 로 셀지. False 면 서로 다른 claim 이면 된다.
#: 정하지 않았다 (CRITERIA §10). 같은 날 두 기관 기록도 비교 근거가 되므로 일단 False.
COUNT_DISTINCT_DATES = False

#: 대처마다 자기 claim_ids 가 있어야 한다. 없으면 그 대처만 버린다.
REQUIRE_SUPPORT_EVIDENCE = True

#: 대처마다 결과가 있어야 한다. 결과가 기록에 없는 대처는 쓰지 않는다.
REQUIRE_SUPPORT_RESULT = True


# ── 쓰는 원칙 (CRITERIA §5) — 프롬프트에 들어간다 ──────────────

DROP_TRAIT = True              # 성격·특성으로 일반화하지 않는다
DROP_DIAGNOSIS = True          # 진단·의학적 해석을 하지 않는다
DROP_PRESCRIPTION = True       # 처방하지 않는다. 기록된 대처와 결과만
KEEP_HEDGES = True             # 원문이 추측이면 추측으로 남긴다
DROP_SENTIMENT = True          # 작성자의 감정 평가는 빼고 사실만
DROP_OTHER_CHILD_NAMES = True  # 다른 아이 이름을 쓰지 않는다
KEEP_FAILED_SUPPORT = True     # 통하지 않은 대처도 남긴다
ONE_SITUATION = True           # 인사이트 하나에 상황 하나

#: 동기화 테스트가 CRITERIA §5 표와 대조할 목록.
WRITING_RULE_FLAGS = (
    "DROP_TRAIT",
    "DROP_DIAGNOSIS",
    "DROP_PRESCRIPTION",
    "KEEP_HEDGES",
    "DROP_SENTIMENT",
    "DROP_OTHER_CHILD_NAMES",
    "KEEP_FAILED_SUPPORT",
    "ONE_SITUATION",
)


# ── 수신 기관 후보 (CRITERIA §7) — 모델이 아니라 코드가 정한다 ──

#: 후보 중 이 인사이트의 근거가 나오지 않은 기관을 추천으로 표시한다.
#: 근거를 쓴 기관은 이미 아는 내용이라, 새로 알게 되는 기관에 우선 의미가 있다.
RECOMMEND_NEW_INSTITUTIONS = True

#: 기관 간 비교 인사이트는 관련 기관 전부를 추천한다. 양쪽 모두 차이를 알아야 한다.
RECOMMEND_ALL_ON_CROSS = True


# ── 출력 크기 ───────────────────────────────────────────────────

#: 한 번에 내보낼 최대 인사이트 수. 정하지 않았다 (CRITERIA §10). None = 제한 없음.
MAX_INSIGHTS = None


# ── Luna (OpenAI 호환) — validation/config.py 와 같은 값 ───────

LUNA_CHAT_PATH = "/v1/chat/completions"
LUNA_MODEL = "gpt-5.6-luna"

#: temperature 는 기본값(1)만 허용된다. 흔들림은 프롬프트와 코드 규칙으로 잡는다.
#: 인사이트는 여러 날의 claims 를 한 번에 넣어 요청이 길어서, 지켜보고 필요하면 늘린다.
LUNA_TIMEOUT = 60.0

#: 본문에 나온 기관 종류가 근거 claim 의 기관 종류 안에 있어야 한다.
#: 이름 대신 종류로 대조한다 — 프롬프트가 기관 이름을 쓰지 말라고 하고,
#: 기관 간 비교 문장은 "학교에서는… 센터에서는…" 처럼 종류로 쓰인다.
#: 한계: 같은 종류 기관이 둘이면(센터 두 곳) 구별하지 못한다.
REQUIRE_INSTITUTION_GROUNDING = True

#: 본문에서 찾을 기관 종류 단어. 입력 claim 의 종류도 함께 본다.
INSTITUTION_TYPES = ("학교", "센터", "학원")

#: 동의하지 않은 기관의 claim 은 인사이트 재료에서 뺀다 (#109).
#: 수신 쪽만 막으면 철회된 기관 기록이 재료로 들어가 다른 기관으로 흘러간다.
#: 동의 목록이 비어 있으면 아무 claim 도 쓰지 않는다 — 모르면 막는다.
FILTER_UNCONSENTED_SOURCES = True