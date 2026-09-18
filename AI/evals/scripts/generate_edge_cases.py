# -*- coding: utf-8 -*-
"""
Matching Agent 신규 엣지케이스 20건 생성
- ① 동명이인 (10건): 성을 뺀 이름만 같은 아이가 여러 명 있을 때
    - 5건: 구분할 생년월일 힌트가 아예 없음 -> expected_status: multi
    - 5건: 생년월일 힌트가 있어서 한 명으로 특정 가능 -> expected_status: auto/review
- ② 이름부분포함 (10건): 등록 아동 B의 이름이 실제로는 무관한 단어 안에
    우연히 포함되는 경우 (예: '은하' in '은하수') -> B로 오매칭하면 안 됨,
    진짜 주인공 A로만 매칭되어야 함

소스: 잇다_synthetic_100_v5.json
저장 위치: AI/evals/generated/ (커밋 대상 아님, 재현 가능한 산출물)
"""
import json
import random
from pathlib import Path

random.seed(21)

SRC_PATH = Path(__file__).parent.parent / "manifests" / "잇다_synthetic_100.json"

with open(SRC_PATH, encoding="utf-8") as f:
    data = json.load(f)
all_children = data["dev"] + data["holdout"]


def child_to_int(child_id: str) -> int:
    if child_id.startswith("dev_child_"):
        return int(child_id.replace("dev_child_", ""))
    return 80 + int(child_id.replace("holdout_child_", ""))


ROSTER = [
    {"child_id": child_to_int(c["child_id"]), "name": c["이름"], "birthdate": c["생년월일"]}
    for c in all_children
]

# 성 뺀 이름(주어진 이름) 기준 그룹화
by_given = {}
for c in all_children:
    given = c["이름"][1:]
    by_given.setdefault(given, []).append(c)

# ===========================================================================
# ① 동명이인 (10건)
# ===========================================================================
dup_groups = {k: v for k, v in by_given.items() if len(v) >= 2}
group_names = list(dup_groups.keys())
random.shuffle(group_names)

homonym_cases = []
jid = 1151

ACTION_TEMPLATES = [
    "{given}이(가) 오늘 낮잠을 푹 잤음.",
    "{given}이(가) 급식을 골고루 먹음.",
    "{given}이(가) 미술 시간에 그림을 그림.",
    "{given}이(가) 바깥놀이 중 그네를 탐.",
    "{given}이(가) 책 읽기 시간에 집중함.",
    "{given}이(가) 블록놀이에 몰두함.",
    "{given}이(가) 노래 시간에 즐겁게 참여함.",
    "{given}이(가) 간식을 잘 먹음.",
    "{given}이(가) 체육 시간에 활발히 움직임.",
    "{given}이(가) 조용히 혼자 놀이를 함.",
]

# 5건: 힌트 없음 -> multi가 정답
for i in range(5):
    given = group_names[i]
    members = dup_groups[given]
    content = ACTION_TEMPLATES[i].format(given=given)
    candidate_ids = sorted(child_to_int(m["child_id"]) for m in members)
    homonym_cases.append({
        "case_id": f"M{jid}",
        "journal_entry_id": jid,
        "hint_name": None,
        "hint_birthdate": None,
        "content": content,
        "roster": ROSTER,
        "expected_child_id": None,
        "expected_status": "multi",
        "expected_multi_reason": "ambiguous_identity",
        "expected_candidates_child_ids": candidate_ids,
        "category": "homonym_no_hint",
        "_note": f"'{given}' 성 없이 언급, 후보 {len(members)}명: {[m['이름'] for m in members]}",
    })
    jid += 1

# 5건: 생년월일 힌트 있음 -> 특정 가능, 정답은 그 한 명
for i in range(5, 10):
    given = group_names[i]
    members = dup_groups[given]
    target = random.choice(members)
    content = ACTION_TEMPLATES[i].format(given=given)
    target_id = child_to_int(target["child_id"])
    homonym_cases.append({
        "case_id": f"M{jid}",
        "journal_entry_id": jid,
        "hint_name": target["이름"],
        "hint_birthdate": target["생년월일"],
        "content": content,
        "roster": ROSTER,
        "expected_child_id": target_id,
        "expected_status": "auto",
        "expected_multi_reason": None,
        "expected_candidates_child_ids": [],
        "category": "homonym_with_hint",
        "_note": f"'{given}' 동명이인 {len(members)}명 중, 표지 생년월일로 {target['이름']} 특정 가능",
    })
    jid += 1

# ===========================================================================
# ② 이름부분포함 (10건)
# ===========================================================================
# (성 뺀 이름, 그 이름을 포함하는 실제 단어/구) 쌍 — 전부 실제로 쓰이는 한국어 단어
TRAP_WORDS = [
    ("지원", "지원서를 작성하는 흉내를 냄"),
    ("지원", "부모님이 지원금 서류를 챙기는 걸 봤다고 말함"),
    ("다인", "다인실 병원 놀이를 하며 의사 역할을 함"),
    ("다인", "다인승 자동차 장난감을 가지고 놂"),
    ("은수", "은수저로 밥을 먹는 흉내를 냄"),
    ("재현", "사고 재현율이 낮다는 뉴스가 나오는 TV 앞을 지나감"),
    ("재현", "실험 재현성에 대한 다큐멘터리가 배경에 흘러나옴"),
    ("아름", "꽃이 아름답게 피었다며 손가락으로 가리킴"),
    ("동현", "소방차 출동현장을 구경하며 신기해함"),
    ("유나", "축구를 좋아해서 유나이티드 팀 로고 스티커를 붙임"),
]

trap_cases = []
used_true_subjects = set()

for i, (trap_given, trap_sentence) in enumerate(TRAP_WORDS):
    trap_members = by_given.get(trap_given)
    if not trap_members:
        continue
    trap_child = trap_members[0]
    trap_id = child_to_int(trap_child["child_id"])

    # 진짜 주인공(A)은 trap_given과 무관한, 아직 안 쓴 다른 아이로 선택
    while True:
        true_subject = random.choice(all_children)
        if true_subject["child_id"] != trap_child["child_id"] and true_subject["child_id"] not in used_true_subjects:
            used_true_subjects.add(true_subject["child_id"])
            break

    a_id = child_to_int(true_subject["child_id"])
    content = f"{true_subject['이름']}이(가) 오늘 {trap_sentence}."

    trap_cases.append({
        "case_id": f"M{jid}",
        "journal_entry_id": jid,
        "hint_name": true_subject["이름"],
        "hint_birthdate": true_subject["생년월일"],
        "content": content,
        "roster": ROSTER,
        "expected_child_id": a_id,
        "expected_status": "auto",
        "expected_hint_mismatch": False,
        "expected_mentioned_child_ids": [a_id],
        "substring_trap_child_id": trap_id,
        "substring_trap_word": trap_given,
        "category": "name_substring_trap",
        "_note": f"본문에 '{trap_given}'({trap_child['이름']})이 우연히 포함되지만, 진짜 주인공은 {true_subject['이름']}",
    })
    jid += 1

all_cases = homonym_cases + trap_cases

OUT_DIR = Path(__file__).parent.parent / "generated"
with open(OUT_DIR / "matching_동명이인_10건.json", "w", encoding="utf-8") as f:
    json.dump(homonym_cases, f, ensure_ascii=False, indent=2)

with open(OUT_DIR / "matching_이름부분포함_10건.json", "w", encoding="utf-8") as f:
    json.dump(trap_cases, f, ensure_ascii=False, indent=2)

print(f"동명이인: {len(homonym_cases)}건 (힌트없음 5 + 힌트있음 5)")
print(f"이름부분포함: {len(trap_cases)}건")