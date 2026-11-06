# 교사 피드백 (딥다이브 2)

> 교사가 AI 초안을 **얼마나 · 무엇을** 고치는지 숫자로 보고, 반복되는 오류를 다음 개선과 평가 케이스로 돌린다.

**모델을 부르지 않는다.** 이미 저장된 것만 읽는다. BE 변경도 필요 없다.

---

## 0. 혼자 해 보기 — BE·DB·Gate 1 화면 없이

팀원을 모으기 전에 **혼자 한 바퀴 돌려** 숫자가 어떻게 생기는지 볼 수 있다.

```bash
# ① 이미 돌린 평가 결과에서 고칠 거리를 뽑는다 (모델 안 부름).
#    결과 파일을 여러 개 주면 합친다 — 한 세트로는 문장이 모자란다.
python evals/scripts/feedback/solo.py draft --out evals/generated/feedback/mine.json \
  --from-run evals/generated/summary/runs/base_off_{dev12,dev12_r2,dev12_r3,names5,outside3,smoke}.json
```

② **`mine.json` 을 편집기로 연다.** 이 파일이 Gate 1 화면을 대신한다.

```
그대로 둔다 → 아무것도 안 함
고친다      → text 를 고침 (id 는 건드리지 않음)
지운다      → 그 { } 덩어리를 통째로 삭제
새로 쓴다   → {"text": "내가 쓴 문장."} 를 더함 (id 없이)
```

```bash
# ③ 편집 결과를 summary_result 가 저장하는 모양으로 바꾼다
python evals/scripts/feedback/solo.py apply evals/generated/feedback/mine.json \
  --out evals/generated/feedback/gate1.json

# ④ 리포트
python evals/scripts/feedback/report.py evals/generated/feedback/gate1.json --rows
```

`apply` 가 만드는 파일은 **DB 를 내보낸 것과 같은 모양**이다. 그래서 혼자 해 본 숫자와
팀이 모은 숫자를 같은 스크립트·같은 표로 비교할 수 있다.

### 문장을 얼마나 모을 수 있나

지금 로컬에 있는 세트로 두 가지가 나온다.

| 합치는 것 | 문장 | 서로 다른 기록 | |
| --- | --- | --- | --- |
| dev12 · names5 · outside3 · smoke | **28** | 23 | 전부 다른 기록이지만 40 미만 |
| 위 + dev12 재실행 2회 | **61** | 23 | 40~60 을 채우지만 24건이 **같은 일지** |

재실행본은 글이 다르지만 **바탕이 된 일지가 같다.** `draft` 가 `#2` 를 붙여 표시하고 경고한다.

- **유지 · 수정 비율**은 그대로 읽어도 된다 — 세는 단위가 "모델이 낸 문장" 이라서다
- **반복 오류는 부풀 수 있다** — 같은 일지에서 같은 실수가 거듭 나오기 때문이다

서로 다른 기록을 늘리는 길은 하나뿐이다. **일지를 더 받는 것** (근찬 `real/dev.json`
또는 시연 일지). 23건이 지금 로컬에 있는 전부다.

---

## 1. 데이터 뽑기

필요한 것은 `summary_result` 의 **두 칸**뿐이다.

```sql
SELECT id AS summary_id, raw_response, claims
FROM summary_result
WHERE claims IS NOT NULL
ORDER BY id;
```

JSON 배열로 내보내 `evals/generated/feedback/` 아래 둔다.

```bash
mysql -N -B itda -e "SELECT JSON_ARRAYAGG(JSON_OBJECT(
  'summary_id', id, 'raw_response', raw_response, 'claims', claims))
  FROM summary_result WHERE claims IS NOT NULL" \
  > evals/generated/feedback/gate1.json
```

> ⚠️ **아동 기록이 들어 있다.** `evals/generated/` 는 gitignore 되어 있다. 레포·공유 서버에 올리지 않고 로컬에서만 다룬다. 실데이터를 쓸 때는 가명만.

## 2. 돌리기

```bash
python evals/scripts/feedback/report.py evals/generated/feedback/gate1.json
python evals/scripts/feedback/report.py evals/generated/feedback/gate1.json --rows
```

## 3. 숫자가 안 나올 때

```
승인된 요약이 없다. 팀원이 Gate 1 을 한 바퀴 눌러야 숫자가 생긴다
```

또는 **유지 100%** 로 나온다. 둘 다 같은 뜻이다 — **아무도 문장 카드를 안 눌렀다.**

그대로 승인하면 모든 문장이 `source: AI` 라 고친 기록이 0이다. 재려면 사람이 **실제로 유지·수정·삭제·추가를 눌러야** 한다.

필요한 양: **요약 10~15건 (문장 40~60개)**. 그보다 적으면 "반복 오류"의 분모가 안 선다.

### 한 번 돌려 본 결과 (2026-11-06, 주은이 교사 역할)

61문장 · `gpt-6-luna` 초안

```
유지  ███████████████████████·····    51   81.0%
수정  ████························     9   14.3%
삭제  ····························     1    1.6%
추가  █···························     2    3.2%

■ 교사가 여러 번 지운 말
  3번  "특별한 일 없이 평소대로 지냈으며"
```

**고친 9건이 전부 `줄임` 한 칸에 들어갔다.** 실제로 고친 이유는 여섯 가지였다 —
군더더기 · 기록에 관한 기록 · 주어 없음 · 빙 돌려 씀 · 다른 아이 행동 · 가족 지칭.

그래서 **지워진 문구 자체를 세는 칸**(`자주뺀말`)을 넣었다. 종류보다 이쪽이 구체적이다.
"줄임 9건" 은 다음에 뭘 고칠지 안 알려 주지만, `"특별한 일 없이 평소대로" 3번` 은 알려 준다.

> 이 숫자는 **한 사람이 한 번** 매긴 것이다. 혼자 돌려 본 결과라 기준선이 아니다.

---

## 되살리는 원리 — 액션은 저장돼 있지 않다

Gate 1 카드(유지·수정·삭제·추가)는 적용되고 버려진다. 남는 건 최종 `claims` 뿐인데, 문장마다 `source` 가 붙어 있어 거기서 되살린다 (`service/approval/Gate1Cards.java`).

| `source` | 교사가 한 일 | 짝 맞추기 |
| --- | --- | --- |
| `AI` | 유지 | 초안과 같다 |
| `EDITED` | 수정 | **근거를 그대로 쓴다** ← 열쇠 |
| `TEACHER` | 추가 | `journal_entry_id` 가 `null` |
| (최종에 없음) | 삭제 | 초안에만 남는다 |

수정은 글만 바꾸고 근거는 안 건드린다. 그래서 **근거로 짝을 짓는다.** 글로 짝지으면 많이 고친 문장이 `삭제 + 추가` 로 둘로 세어져 숫자가 통째로 어긋난다 (`test_kinds.py` 가 이걸 지킨다).

---

## 이 분류가 **못 하는 것**

종류는 **무엇이 바뀌었나**지 **왜 바꿨나**가 아니다.

```
"세 번 놀았다" → "다섯 번 놀았다"
  코드가 아는 것  숫자가 바뀌었다
  모르는 것       사실을 바로잡은 건가, 표현을 다듬은 건가

문장이 사라짐
  코드가 아는 것  삭제됐다
  모르는 것       군더더기라 지운 건가, 공유하면 안 될 내용이라 지운 건가
```

**사실 오류**와 **프라이버시**를 가르려면 교사에게 물어야 한다 (Gate 1 에 이유 칸). 지금은 넣지 않는다 — 이유를 담으려면 DTO 한 칸이 아니라 **저장 테이블 · 삭제한 문장의 이유 자리 · 읽는 API** 까지 필요하고, Gate 1 은 선생님이 매일 쓰는 화면이라 단계를 늘리는 값이 비싸다.

그래서 **이 분류로는 "표현을 몇 번 고쳤나" 까지만 말한다.** 그 이상을 주장하지 않는다.

### 삭제에 섞인 것

`raw_response` 는 모델이 낸 원본이라 **근거 검사(`ground`)가 버린 문장도 들어 있다.** 교사 삭제와 코드 삭제를 이 행만 보고 가를 수 없다.

실데이터에서 코드가 버린 문장은 **575 중 2건(0.35%)** 이라 거의 교사 삭제지만, 0이라고 적지는 않는다 (`AI/summary/CRITERIA.md §2-b`).

---

## 파일

| | |
| --- | --- |
| `kinds.py` | 짝 맞추기 · 종류 가르기 (순수 함수, 모델 없음) |
| `report.py` | 내보낸 JSON → 한 장짜리 리포트 |
| `solo.py` | 혼자 교사 노릇 해 보기 (0절) |
| `test_kinds.py` | 23건. `python evals/scripts/feedback/test_kinds.py` |
