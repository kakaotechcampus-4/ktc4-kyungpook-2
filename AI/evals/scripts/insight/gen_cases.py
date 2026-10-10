# AI/evals/scripts/insight/gen_cases.py
"""
인사이트 합성 테스트 데이터를 만든다. LLM 을 부르지 않고, 시드를 고정해 매번 같다.

정답은 우리가 심는다.
  - 반복 패턴: 같은 상황 3회 (대처 성공·실패 섞음) → 그 claim 들을 근거로 한 인사이트
  - 기관 간 차이: 같은 상황, 학교와 센터 반응이 다름 → cross_institution 인사이트
  - 대조군: 서로 무관한 관찰만 → 빈 배열

채점: 출력 인사이트의 claim_ids 가 심은 claim 과 MIN_EVIDENCE_CLAIMS 개 이상 겹치면 찾은 것으로 본다.
"""

import json
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(AI_DIR))

from insight.schemas import InsightInput  # noqa: E402

random.seed(11)
OUT = AI_DIR / "evals" / "generated" / "insight" / "insight_cases.json"

INSTITUTIONS = {1: "학교", 2: "센터", 3: "학원"}
NAMES = ["박서아", "최도윤", "정하린", "윤시우", "강지호", "한예린", "오민재", "서지안", "임하준", "송유나",
         "배서진", "조은우", "권다온", "문시현", "신하율", "류지후", "남윤서", "홍도현", "고아린", "장태윤",
         "유서하", "표지율", "진하람", "육도윤", "추예서", "변시온"]
PEERS = ["김도하", "이수아", "백현우", "차예준", "노하준", "탁서윤", "엄지환"]

# (claim 문장, 원본 일지 인용) — [기본, 통한 대처, 통하지 않은 대처]
REPEAT = [
    [("활동이 바뀔 때 교실 밖으로 나가려 했다.", "교실 밖으로 나가려 함"),
     ("활동이 바뀔 때 교실 밖으로 나가려 했고, 그림 카드로 다음 활동을 보여주자 자리로 돌아왔다.", "그림 카드로 다음 활동을 보여주자 자리로 돌아옴"),
     ("활동이 바뀔 때 교실 밖으로 나가려 했고, 이름을 크게 부르자 더 거부했다.", "이름을 크게 부르자 더 거부함")],
    [("급식 시간에 새 반찬을 밀어냈다.", "새 반찬을 밀어냄"),
     ("급식 시간에 새 반찬을 밀어냈고, 작은 접시에 조금 덜어주자 한 입 먹어봤다.", "작은 접시에 덜어주자 한 입 먹음"),
     ("급식 시간에 새 반찬을 밀어냈고, 다 먹어야 한다고 하자 울었다.", "다 먹어야 한다고 하자 울음")],
    [("큰 소리가 나자 귀를 막고 웅크렸다.", "귀를 막고 웅크림"),
     ("큰 소리가 나자 귀를 막고 웅크렸고, 조용한 구석으로 데려가자 진정했다.", "조용한 구석으로 데려가자 진정함"),
     ("큰 소리가 나자 귀를 막고 웅크렸고, 괜찮다고 계속 말을 걸자 더 크게 울었다.", "계속 말을 걸자 더 크게 울음")],
    [("자유놀이를 끝낼 때 장난감을 놓지 않았다.", "장난감을 놓지 않음"),
     ("자유놀이를 끝낼 때 장난감을 놓지 않았고, 타이머로 남은 시간을 보여주자 스스로 정리했다.", "타이머를 보여주자 스스로 정리함"),
     ("자유놀이를 끝낼 때 장난감을 놓지 않았고, 장난감을 바로 치우자 바닥에 누웠다.", "장난감을 치우자 바닥에 누움")],
]

# 같은 상황, 기관마다 다른 반응
CROSS = [
    {"학교": ("신발을 신을 때 혼자 신었다.", "신발을 혼자 신음"),
     "센터": ("신발을 신을 때 교사 도움이 필요했다.", "신발 신기를 도와줌")},
    {"학교": ("줄을 설 때 차례를 기다렸다.", "차례를 기다림"),
     "센터": ("줄을 설 때 앞으로 끼어들었다.", "앞으로 끼어듦")},
    {"학교": ("손을 씻을 때 순서대로 씻었다.", "순서대로 손을 씻음"),
     "센터": ("손을 씻을 때 물놀이를 하며 끝내지 않았다.", "물놀이를 하며 끝내지 않음")},
    {"학교": ("인사할 때 먼저 손을 흔들었다.", "먼저 손을 흔듦"),
     "센터": ("인사할 때 교사를 쳐다보지 않았다.", "교사를 쳐다보지 않음")},
]

# 서로 무관한 하루짜리 관찰. 아이 한 명 안에서는 겹치지 않게 뽑는다.
NOISE = [
    ("블록을 높이 쌓았다.", "블록을 높이 쌓음"), ("낮잠을 푹 잤다.", "낮잠을 잘 잠"),
    ("크레파스로 해를 그렸다.", "해를 그림"), ("그림책을 끝까지 넘겼다.", "그림책을 끝까지 봄"),
    ("공을 두 손으로 받았다.", "공을 받음"), ("물을 스스로 따라 마셨다.", "물을 따라 마심"),
    ("노래에 맞춰 손뼉을 쳤다.", "손뼉을 침"), ("퍼즐 네 조각을 맞췄다.", "퍼즐을 맞춤"),
    ("점토로 공을 만들었다.", "점토로 공을 만듦"), ("화분에 물을 줬다.", "화분에 물을 줌"),
    ("가방을 고리에 걸었다.", "가방을 걺"), ("스티커를 종이에 붙였다.", "스티커를 붙임"),
    ("모래놀이를 했다.", "모래놀이를 함"), ("색종이를 반으로 접었다.", "색종이를 접음"),
    ("간식을 남기지 않았다.", "간식을 다 먹음"), ("자기 이름 카드를 골랐다.", "이름 카드를 고름"),
]

entry_counter = [1000]


def draft(inst_id, text, quote):
    entry_counter[0] += 1
    return {"institution_id": inst_id, "text": text, "quote": quote, "journal_entry_id": entry_counter[0]}


def assemble(child_id, name, drafts, dates):
    """날짜를 붙이고, 날짜별로 summary_id 를 주고, claim_id = {summary_id}-{순서} 로 만든다."""
    for d, date in zip(drafts, dates):
        d["entry_date"] = date
    by_date = defaultdict(list)
    for d in drafts:
        by_date[d["entry_date"]].append(d)

    claims = []
    for date in sorted(by_date):
        summary_id = child_id * 100 + int(date[-2:])
        for idx, d in enumerate(by_date[date]):
            d["claim_id"] = f"{summary_id}-{idx}"
            claims.append({
                "claim_id": d["claim_id"], "text": d["text"], "entry_date": date,
                "institution_id": d["institution_id"],
                "institution_type": INSTITUTIONS[d["institution_id"]],
                "evidence": [{"journal_entry_id": d["journal_entry_id"], "quote": d["quote"]}],
            })

    consented = random.choice([[1, 2, 3], [1, 2], [1, 2, 3]])
    return {
        "child_id": child_id, "child_name": name,
        "period_from": "2026-01-01", "period_to": "2026-01-31",
        "claims": claims,
        "consented_institutions": [{"institution_id": i, "institution_type": INSTITUTIONS[i]} for i in consented],
    }


def pick_dates(n):
    return sorted(f"2026-01-{d:02d}" for d in random.sample(range(5, 31), n))


def make_case(child_id, category):
    name = NAMES[child_id - 1]
    noise = [draft(random.choice([1, 2]), t, q) for t, q in random.sample(NOISE, 5 if category != "control" else 8)]
    planted, forbidden, cross = [], [], False

    if category == "repeat":
        texts = REPEAT[child_id % len(REPEAT)]
        planted = [draft(random.choice([1, 2]), t, q) for t, q in texts]
        if child_id % 2 == 0:  # 절반은 다른 아이 이름을 섞는다 → content 에 새면 안 됨
            peer = random.choice(PEERS)
            planted[0]["text"] += f" 옆에 있던 {peer}이(가) 지켜봤다."
            forbidden.append(peer)
    elif category == "cross":
        pair = CROSS[child_id % len(CROSS)]
        planted = [draft(1, *pair["학교"]), draft(2, *pair["센터"]), draft(1, *pair["학교"])]
        cross = True
    elif category == "names":
        # 다른 아이 이름이 든 claim 갈래. 요약 익명화가 실패해 이름이 올라온 경우를 흉내 낸다.
        # 홀수는 반복 패턴, 짝수는 기관 간 차이. 패턴 claim 두 개에 서로 다른 아이 이름을 섞는다.
        if child_id % 2:
            texts = REPEAT[child_id % len(REPEAT)]
            planted = [draft(random.choice([1, 2]), t, q) for t, q in texts]
        else:
            pair = CROSS[child_id % len(CROSS)]
            planted = [draft(1, *pair["학교"]), draft(2, *pair["센터"]), draft(1, *pair["학교"])]
        for d, peer in zip(planted[:2], random.sample(PEERS, 2)):
            d["text"] += f" 옆에 있던 {peer}이(가) 지켜봤다."
            forbidden.append(peer)
        cross = len({d["institution_id"] for d in planted}) >= 2

    drafts = planted + noise
    random.shuffle(drafts)
    inp = assemble(child_id, name, drafts, pick_dates(len(drafts)))
    InsightInput(**inp)  # 계약에 안 맞으면 여기서 터진다

    expected_patterns = []
    if planted:
        expected_patterns.append({"claim_ids": [d["claim_id"] for d in planted], "cross_institution": cross})

    return {
        "case_id": f"I{child_id:03d}",
        "category": category,
        "input": inp,
        "expected": {
            "patterns": expected_patterns,
            "empty": category == "control",
            "forbidden_terms": forbidden,
        },
    }


def main():
    plan = ["repeat"] * 6 + ["cross"] * 4 + ["control"] * 10 + ["names"] * 6
    cases = [make_case(i + 1, cat) for i, cat in enumerate(plan)]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"생성: {len(cases)}건 → {OUT}")
    print(Counter(c["category"] for c in cases))


if __name__ == "__main__":
    main()