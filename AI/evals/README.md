# 매칭 에이전트 평가

테스트 데이터를 매칭 에이전트에 돌리고 채점한다.

```bash
cd AI
python evals/run_eval.py ~/Downloads/matching_inputs.json /tmp/result.json
python evals/score.py /tmp/result.json
```

실행과 채점을 나눈 것은 의도한 것이다. 실행은 LLM 을 부르므로 1,070건에 약 8분이
걸리지만, 채점은 결과 파일만 읽으므로 즉시 끝난다. 임계값을 바꿔가며 여러 번
채점할 때 매번 다시 부르지 않기 위해서다.

옵션: `--limit N` (앞에서 N 건만), `--workers N` (동시 호출 수, 기본 8).
`AI/.env` 에 `LUNA_API_URL` 과 `LUNA_API_KEY` 가 있어야 한다.

## 입력 형식

`MatchingInput` 배열에 정답 필드를 얹은 것이다. **정답 필드는 전부 선택**이고,
없는 항목은 그 지표를 건너뛴다. 데이터가 늘어날 때 스크립트를 고치지 않아도 된다.

| 필드 | 뜻 |
|---|---|
| `expected_child_id` | 정답 아동 ID. `null` 이면 "명부에 없는 아이" (정답 = `unmatched`) |
| `expected_status` | `auto` / `review` / `multi` / `unmatched` |
| `expected_hint_mismatch` | 표지를 뒤집는 것이 맞는 케이스인지 |
| `expected_mentioned_child_ids` | 본문에 이름이 등장해야 하는 아이들 |

형식은 [expected_fields.example.json](expected_fields.example.json) 참고.

`status` 를 따로 적어야 하는 이유: `matched_child_id` 가 맞아도 `status` 가 틀릴 수
있다. 표지를 뒤집은 판정은 확신이 0.99 여도 `auto` 가 아니라 `review` 로 내려간다
(사람이 봐야 하므로). 정답을 ID 하나로만 적으면 이 구분이 채점에 안 잡힌다.

## 읽는 법

맨 위의 **오매칭**이 가장 중요하다. `auto` 는 사람 확인을 건너뛰므로, 여기서
틀리면 다른 아이의 기록이 부모에게 간다. 다른 지표가 좋아도 이 값이 0 이 아니면
`config.py` 의 `TAU_AUTO` 를 올려야 한다.

`1순위 정답률` 과 `정답 도달률` 은 다르다. `multi` 는 `matched_child_id` 가 비어
있어 1순위 기준으로는 오답이지만, 교사에게 후보로 정답을 보여주므로 한 번 고르면
끝난다. 두 숫자를 같이 봐야 한다.

## 데이터는 커밋하지 않는다

가상 이름이어도 아동 관찰 기록 형태의 파일은 레포에 넣지 않는다. git 히스토리는
지워지지 않는다. `.gitignore` 가 이 디렉터리의 `*.json` 을 막아두었다 (예시 파일 제외).

## 지금 데이터의 한계

2026-09-16 기준 1,070건은 **정답이 항상 표지(`hint_name`)와 같다.** 본문을 아예
읽지 않고 표지만 명부에서 찾는 3 줄짜리 코드가 100% 를 받는다는 뜻이라, 이 데이터로는
에이전트가 베이스라인을 넘는지 알 수 없다. 아래 셋이 들어와야 측정이 시작된다.

1. 표지는 A 인데 본문이 명백히 B — `expected_hint_mismatch: true`
2. 표지 아이가 주인공이고 다른 아이는 스쳐 지나감 — `multi` 가 아니라 `auto`/`review`
3. 표지가 비어 있음 (`hint_name: null`) — 근거가 없으면 `unmatched` 가 맞는지
