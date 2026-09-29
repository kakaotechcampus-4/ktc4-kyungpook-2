"""
변형(metamorphic) 테스트 케이스 자동 생성 — 새 문장을 사람이 떠올리지 않아도 되게.

원리: 정답을 아는 dev 문장(seed)에 "결과가 어떻게 바뀌어야 하는지 이미 아는" 변형을
가한다. 정답 라벨은 사람이 새로 다는 게 아니라 변형 규칙에서 자동으로 나온다.

    PASS 문장 + 다른 아이 이름 삽입         → REVIEW [다수아동언급]
    PASS 문장 + 다른 아이 개인정보 삽입     → BLOCK  [개인정보표현]
    PASS 문장 + 본인 개인정보(다양한 형식)  → BLOCK  [개인정보표현]
    이슈 문장 + 무해한 문장 덧붙이기(희석)  → 원래 라벨 그대로
    PASS 문장 + PASS 문장 이어붙이기        → PASS 그대로 (과탐 확인)

LLM 호출 없음 → 생성 비용 0. 호출은 run_suite.py 로 돌릴 때만 든다.

⚠️ seed는 dev에서만 뽑는다. holdout은 절대 건드리지 않는다.
⚠️ 이건 "처음 보는 표현"을 대신하지 못한다. 문장 틀은 결국 우리가 썼다.
   최종 확인은 여전히 팀원이 새로 쓴 블라인드 세트로 한다 (README 참고).

실행 (AI 폴더에서):
    python3 evals/scripts/validation/gen_metamorphic.py            # 관계당 30건
    python3 evals/scripts/validation/gen_metamorphic.py --per 10   # 관계당 10건
"""
import argparse
import json
import random
import re
from pathlib import Path

GENERATED_DIR = Path(__file__).parent.parent.parent / "generated" / "validation"
INPUTS_PATH = GENERATED_DIR / "validation_inputs.json"
OUT_PATH = GENERATED_DIR / "metamorphic_cases.json"

# ── 판정 기준 문서에서 아직 안 정한 것 ──────────────────────────────
# None = 미정 → 해당 변형은 만들지 않는다. 팀이 정하면 "BLOCK"/"REVIEW"/"PASS"로 채운다.
# 정한 값은 판정 기준 문서와 반드시 같아야 한다.
POLICY = {
    "생일_월일": None,          # "생일(11월 21일)" — 연도 없는 생일이 개인정보인가
    "가족_진단명": None,        # "형이 ADHD 진단" — 다른 사람 진단명을 막는가
    "의료정보_진단명없음": None,  # "소견서상 중증" — 진단명 없는 의료정보를 막는가
    "이름없는_또래언급": None,    # "친구가 넘어지자" — 이름 없는 또래 언급도 다수아동언급인가
}

# 이름 없는 또래 언급. 위 정책이 "PASS"로 정해지기 전까지는 이런 문장을 재료로 쓰지 않는다
# (정답이 정해지지 않은 문장을 섞으면 과탐인지 정답인지 채점이 불가능해서).
PEER_RE = re.compile(r"친구|짝꿍|또래|아이들|다른 아이")


# ── 이름 도우미 ─────────────────────────────────────────────────
def has_batchim(ch: str) -> bool:
    code = ord(ch) - 0xAC00
    return 0 <= code < 11172 and code % 28 != 0


def nickname(full_name: str) -> str:
    """안도영 → 도영이, 한시우 → 시우 (일지에서 흔한 호칭)"""
    given = full_name[1:] if len(full_name) >= 3 else full_name
    return given + "이" if has_batchim(given[-1]) else given


def name_forms(full_name: str) -> list[str]:
    return [full_name, nickname(full_name)]


def josa(word: str) -> dict:
    """이름 뒤 조사를 받침에 맞춰 붙인 형태들. 조지원가 → 조지원이 같은 어색함 방지."""
    b = has_batchim(word[-1])
    return {
        "n": word,
        "wa": word + ("과" if b else "와"),
        "ga": word + ("이" if b else "가"),
        "eun": word + ("은" if b else "는"),
        "rang": word + ("이랑" if b else "랑"),
    }


# ── 가짜 개인정보 (전부 무작위, 실존 정보 아님) ──────────────────────
def pii_sentence(who: str, r) -> str:
    j = josa(who)
    kind = r.choice(["phone", "birth", "address"])
    if kind == "phone":
        v = f"010-{r.randint(1000, 9999)}-{r.randint(1000, 9999)}"
        return r.choice([
            f"{j['n']} 어머니 연락처는 {v}.",
            f"{j['ga']} 보호자 번호({v})를 알려줌.",
            f"하원 관련해서 {j['n']} 아버지께 {v}로 연락드림.",
        ])
    if kind == "birth":
        y, m, d = r.randint(2015, 2020), r.randint(1, 12), r.randint(1, 28)
        v = r.choice([f"{y}년 {m}월 {d}일생", f"생년월일이 {y}.{m:02d}.{d:02d}", f"{str(y)[2:]}{m:02d}{d:02d}생"])
        return f"{j['eun']} {v}."
    gu = r.choice(["북구", "중구", "수성구", "달서구", "동구"])
    dong = r.choice(["산격동", "복현동", "범어동", "대명동", "신암동"])
    v = f"대구 {gu} {dong} {r.randint(1, 999)}-{r.randint(1, 30)}"
    return r.choice([f"{j['eun']} {v}에 산다고 함.", f"{j['n']} 집 주소는 {v}."])


# ── 문장 틀 (무해한 틀 — 라벨은 끼워 넣는 요소가 결정) ────────────────
OTHER_CHILD_FRAMES = [
    "{wa} 같이 블록 놀이를 함.",
    "점심시간에 {n} 옆자리에 앉음.",
    "{n}도 오늘 같은 활동에 참여함.",
    "바깥놀이 때 {rang} 술래잡기를 함.",
    "{ga} 먼저 인사를 건넴.",
]


def join(a: str, b: str, r) -> str:
    a, b = a.strip(), b.strip()
    return f"{a} {b}" if r.random() < 0.5 else f"{b} {a}"


# ── 변형 관계 ────────────────────────────────────────────────────
def make_case(seed, relation, content, verdict, issues):
    return {
        "case_id": f"MT_{relation}_{seed['case_id']}",
        "journal_entry_id": seed.get("journal_entry_id"),
        "subject_child_id": seed.get("subject_child_id"),
        "subject_name": seed.get("subject_name"),
        "content": content,
        "expected_verdict": verdict,
        "expected_issue_types": issues,
        "dataset": "metamorphic",
        "relation": relation,
        "seed_case_id": seed["case_id"],
    }


def other_name(seed, pool, r):
    candidates = [n for n in pool if n != seed.get("subject_name")]
    return r.choice(name_forms(r.choice(candidates)))


def mr_other_child(seed, pool, passes, r):
    frame = r.choice(OTHER_CHILD_FRAMES).format(**josa(other_name(seed, pool, r)))
    return make_case(seed, "다른아이등장", join(seed["content"], frame, r), "REVIEW", ["다수아동언급"])


def mr_other_pii(seed, pool, passes, r):
    frame = pii_sentence(other_name(seed, pool, r), r)
    return make_case(seed, "다른아이개인정보", join(seed["content"], frame, r), "BLOCK",
                     ["개인정보표현", "다수아동언급"])  # 다른 아이 이름이 들어가니 다수아동언급도 정답


def mr_self_pii(seed, pool, passes, r):
    frame = pii_sentence(r.choice(name_forms(seed["subject_name"])), r)
    return make_case(seed, "본인개인정보", join(seed["content"], frame, r), "BLOCK", ["개인정보표현"])


def mr_dilute(seed, pool, passes, r):
    """이슈 문장 앞뒤에 무해한 문장을 붙여도 라벨은 그대로여야 한다."""
    fillers = [p["content"] for p in r.sample(passes, k=r.randint(1, 3))]
    # 템플릿 문장 끝의 "(관찰 사실보다 … 서술)" 같은 정답 힌트 괄호는 떼고 쓴다
    core = re.sub(r"\s*\([^()]*(서술|표현|언급)[^()]*\)\s*$", "", seed["content"])
    parts = fillers + [core]
    r.shuffle(parts)
    return make_case(seed, "희석", " ".join(parts), seed["expected_verdict"], seed["expected_issue_types"])


def mr_pass_concat(seed, pool, passes, r):
    """정상 + 정상 = 정상. 길어졌다고 막으면 과탐."""
    extra = r.choice([p for p in passes if p["case_id"] != seed["case_id"]])
    return make_case(seed, "정상연결", join(seed["content"], extra["content"], r), "PASS", [])


def mr_birthday_md(seed, pool, passes, r):
    m, d = r.randint(1, 12), r.randint(1, 28)
    frame = r.choice(["오늘 생일({m}월 {d}일)이어서 파티를 함.", "{m}월 {d}일이 생일이라 친구들이 축하해줌."]).format(m=m, d=d)
    v = POLICY["생일_월일"]
    return make_case(seed, "생일월일", join(seed["content"], frame, r), v, ["개인정보표현"] if v != "PASS" else [])


# (관계 이름, 함수, seed 종류, 정책 키)
RELATIONS = [
    ("다른아이등장", mr_other_child, "pass", None),
    ("다른아이개인정보", mr_other_pii, "pass", None),
    ("본인개인정보", mr_self_pii, "pass", None),
    ("희석", mr_dilute, "issue", None),
    ("정상연결", mr_pass_concat, "pass", None),
    ("생일월일", mr_birthday_md, "pass", "생일_월일"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per", type=int, default=30, help="관계당 생성 건수")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    r = random.Random(args.seed)

    with open(INPUTS_PATH, encoding="utf-8") as f:
        inputs = json.load(f)

    dev = [c for c in inputs if c["dataset"] == "dev" and c.get("subject_name")]
    passes = [c for c in dev if c["expected_verdict"] == "PASS"]
    issues = [c for c in dev if c["expected_verdict"] != "PASS"]
    if POLICY["이름없는_또래언급"] != "PASS":
        passes = [c for c in passes if not PEER_RE.search(c["content"])]
        issues = [c for c in issues
                  if "다수아동언급" in c["expected_issue_types"] or not PEER_RE.search(c["content"])]
        print(f"  또래 언급 문장 제외 후 재료: 정상 {len(passes)}건, 이슈 {len(issues)}건")
    pool = sorted({c["subject_name"] for c in inputs if c.get("subject_name")})

    cases = []
    for name, fn, kind, policy_key in RELATIONS:
        if policy_key and POLICY[policy_key] is None:
            print(f"  건너뜀: {name} (판정 기준 미정 — POLICY['{policy_key}'])")
            continue
        seeds = passes if kind == "pass" else issues
        for seed in r.sample(seeds, k=min(args.per, len(seeds))):
            cases.append(fn(seed, pool, passes, r))
        print(f"  {name}: {min(args.per, len(seeds))}건")

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(cases, f, ensure_ascii=False, indent=2)
    print(f"생성 완료: {len(cases)}건 → {OUT_PATH}")


if __name__ == "__main__":
    main()
