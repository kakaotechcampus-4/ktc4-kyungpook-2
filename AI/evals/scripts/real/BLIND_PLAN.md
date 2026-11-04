# 실데이터 블라인드 실행 계획

> 2026-11-04 작성 · **실행 전에 커밋한다.** 결과를 본 뒤 이 문서의 기준을 바꾸지 않는다.

## 세트 (`AI/evals/generated/real/blind/`, git-ignored)

| 파일 | 내용 | 만든 사람 |
| --- | --- | --- |
| `test.json` | 실제 센터 일지 32건 (8·9·11·12월, 아동 10명, 가명). 개발 중 열어 보지 않음 | 근찬 (PDF 추출) |
| `synthetic_school_blind.json` | 가상 학교 일지 20건 — 패턴용 10 + 묶으면 안 되는 기록 10 | Claude (정답표 `expect` 를 보고 작성) |
| `synthetic_school_blind_answer.json` | 정답표 — 패턴 BP1~BP10, 묶으면 안 되는 기록 10건 | 주은 |

- 한계: 학교 일지를 정답표를 보고 썼다. 인사이트가 찾기 쉬운 문장일 수 있어서, 정답 패턴 점수는 이 점을 함께 적는다

## 설정

- 이 문서를 커밋한 커밋에서 돌린다 (루프 넣기 전 develop 과 같은 에이전트 코드)
- 매칭 #306 플래그 켬 · 검증 v1.4 · 인사이트 CRITERIA v0.9.5 · Luna 넘칠 때만 low · 수정 루프 없음
- 인사이트 기간 30일 (dev 실행과 같음)

```bash
caffeinate -i python evals/scripts/real/run_real.py --months 08,09,11,12 --real blind/test.json \
  --school blind/synthetic_school_blind.json --answer blind/synthetic_school_blind_answer.json
```

## 횟수

- **1회.** 실행 도중 Luna 연결이 끊기거나 노트북이 잠들어 실행이 무효가 되면, 고치지 않고 같은 명령으로 한 번 다시 돌린다

## 지표와 dev 기준

| 에이전트 | 지표 | dev (4~6월, 5회) | 블라인드에서 문제로 볼 선 |
| --- | --- | --- | --- |
| 매칭 | 정답 / 오매칭 | 66~68 / 68 · 오매칭 0 | 오매칭 1건 이상 |
| 검증 | 치명 (BLOCK 이어야 하는데 PASS) | 0 | 1건 이상 |
| 요약 | 성공 · 반영 안 된 일지 · 버린 문장 | 66/66 · 0 · 2~3 / 210 | 실패 또는 반영 안 된 일지 1건 이상 |
| 인사이트 | 정답 패턴 | 9~10 / 10 | 숫자를 그대로 적는다 (dev 보다 낮을 수 있음) |
| 인사이트 | 관계없는 기록 묶음 · 하루 기록 인사이트 | 0~1 · 0 | 각각 2건 이상 · 1건 이상 |

- 검증 치명 여부는 실행 뒤 BLOCK·REVIEW 일지를 사람이 확인해 정한다

## 실행 뒤 규칙

- 결과를 보고 프롬프트·기준·코드를 고치지 않는다. 고친 뒤에는 이 세트로 다시 재지 않는다
- 점수표(`AI/evals/SCOREBOARD.md`)에 결과와 한계를 적고 "소모됨"으로 표시한다
- 결과 JSON·HTML 은 `generated/real/runs/` 에만 둔다 (커밋하지 않음)
