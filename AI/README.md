# AI

관찰 기록 파이프라인의 에이전트와 평가 도구를 관리합니다.

```
Local Record → ①매칭 → ②검증 → ③요약 → [Gate 1] → ④인사이트 → [Gate 2] → ⑤공유
               └─── 구현 완료 ───┘
```

| 디렉터리 | 내용 |
| --- | --- |
| `matching/` | 매칭 에이전트 — 기록 한 줄이 어느 아동의 것인지 판정 |
| `validation/` | 검증 에이전트 — 그 기록을 저장해도 안전한지 판정 |
| `evals/` | 평가 스크립트 — 에이전트별로 나뉘어 있습니다 ([매칭](evals/scripts/matching/README.md)) |
| `main.py` | FastAPI 서버 |

---

## 실행

`.env` 를 만들고 Luna 키를 채웁니다. 형식은 [.env.example](.env.example) 참고.

```bash
cp .env.example .env     # LUNA_API_URL, LUNA_API_KEY 채우기
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

도커로 띄울 때는 레포 루트에서 실행합니다.

```bash
docker compose -f infra/docker/compose.yaml up -d --build ai
```

> 서버에서는 `127.0.0.1:8000` 에만 바인드되고 Caddy 가 라우팅하지 않습니다.
> 인증이 없는 엔드포인트라 외부에 열지 않습니다 — 확인하려면 서버 안에서 `curl localhost:8000` 을 씁니다.

### 배포

`develop` 에 병합되면 [AI CI/CD](../.github/workflows/ai-ci-cd.yml) 가 이미지를 빌드·확인한 뒤
서버의 AI 컨테이너만 교체합니다. `*.md` 와 `evals/` 만 바꾸면 재배포하지 않습니다.
`evals/` 는 이미지에 넣지 않고 서버 checkout 을 마운트해 씁니다.

배포 확인은 `/health` 와 모델을 부르지 않는 `/matching` 한 건으로 하고, 서버 `AI/.env` 에
Luna 키가 없으면 배포가 실패합니다. 실패했을 때 다시 배포하는 방법과 서버 절차는
[인프라 문서](../infra/README.md#ai-cicd) 를 참고하세요.

---

## API

| 메서드 | 경로 | 역할 |
| --- | --- | --- |
| `GET` | `/health` | 상태 확인. Luna 키가 설정돼 있으면 200, 없으면 503 (`luna_configured` 로 함께 알려줍니다) |
| `POST` | `/matching` | 기록 한 줄을 받아 어느 아동의 것인지 판정합니다 |
| `POST` | `/validation` | 그 기록을 저장해도 안전한지 판정합니다. Luna 호출이 실패하면 503 (정규식으로 개인정보가 잡힌 경우는 200 + `BLOCK`) |
| `GET` | `/llm-test` | Luna 연결 확인용 |

---

### `POST /matching`

계약은 [matching/schemas.py](matching/schemas.py) 가 유일한 기준입니다. 아래는 요약입니다.

**입력**

```json
{
  "journal_entry_id": 1041,
  "content": "자유놀이 중 블록을 높이 쌓았다.",
  "roster": [{ "child_id": 1, "name": "송준호", "birthdate": "2019-11-26" }],
  "raw_record_id": 77,
  "entry_date": "2026-09-20",
  "hint_name": "박서연",
  "hint_birthdate": "2019-05-05"
}
```

- `roster` — 해당 기관의 아동 전체. 백엔드가 채웁니다
- `hint_name` / `hint_birthdate` — 파일 표지(파일명)에서 뽑은 값. **선택 필드**이고 `null` 이어도 동작합니다

**출력**

```json
{
  "journal_entry_id": 1041,
  "status": "auto",
  "matched_child_id": 8,
  "confidence": 0.99,
  "hint_mismatch": false,
  "evidence": [{ "start": 0, "end": 3 }],
  "mentioned_child_ids": [8],
  "multi_reason": null,
  "candidates": [],
  "llm_called": true
}
```

| status | 뜻 | 화면이 할 일 |
| --- | --- | --- |
| `auto` | 근거가 충분하고 부정 신호 없음 | 다음 단계로 |
| `review` | 후보는 하나인데 근거가 약함 | "이 아이 맞나요?" 확인 |
| `multi` | 후보가 둘 이상 | `candidates` 중에서 고르게 |
| `unmatched` | 명부에 해당하는 아이 없음 | 이름으로 직접 검색 |

`mentioned_child_ids` 는 본문에 이름이 남은 **다른 아이**까지 담습니다. 검증 단계가 개인정보
노출을 잡는 근거이므로 비우면 안 됩니다.

**AI 는 DB 를 만지지 않습니다.** 결과 저장과 상태 관리는 전부 백엔드가 합니다.
재시도해도 안전하도록 에이전트는 상태를 갖지 않습니다.

---

### `POST /validation`

계약은 [validation/schemas.py](validation/schemas.py) 가 유일한 기준입니다.

**입력**

```json
{
  "journal_entry_id": 1041,
  "content": "박서연이 자유놀이 시간에 블록을 높이 쌓았다.",
  "subject_child_id": 8,
  "subject_name": "박서연"
}
```

- `subject_child_id` / `subject_name` — **매칭 결과에서 넘겨야 합니다.** 판정 대상이
  누구인지 모르면 귀속 검증이 성립하지 않아, 코드가 강제로 `REVIEW` 로 보냅니다

**출력**

```json
{
  "journal_entry_id": 1041,
  "verdict": "BLOCK",
  "issue_types": ["개인정보표현"],
  "evidence": [{ "start": 23, "end": 36 }]
}
```

| verdict | 뜻 | 백엔드가 할 일 |
| --- | --- | --- |
| `PASS` | 문제 없음 | 요약으로 넘김 |
| `REVIEW` | 교사 확인 필요 | 수정 요청 큐로 |
| `BLOCK` | 그대로 두면 위험 | **요약으로 넘기지 않음** |

| 등급 | 이슈 유형 |
| --- | --- |
| `BLOCK` | 진단명 · 개인정보표현 |
| `REVIEW` | 확정적표현 · 다수아동언급 · 추측성표현 · 감정적표현 · 위험행동표현 |

전화번호·주민번호 형식은 모델 판단 없이 정규식으로 항상 `BLOCK` 입니다. 모델 호출이
실패해도 이 판정은 유지됩니다.

`evidence` 는 겹치지 않는 최소 구간만 담습니다. 구조적 히트와 모델 인용이 같은 곳을
가리키면 좁은 쪽만 남습니다.

---

## 매칭 그래프 구조

```
START → extract → shortlist ─┬─(이름 하나가 명확)──────────→ decide → END
                             └─(그 외)→ llm_judge ─────────┘
```

| 노드 | 하는 일 | LLM |
| --- | --- | --- |
| `extract` | 본문에서 명부의 이름이 등장하는 지점을 찾음 | 안 씀 |
| `shortlist` | 아이별 점수 집계, 표지 힌트 반영, 겹치는 이름 정리 | 안 씀 |
| `llm_judge` | 애매한 경우만 모델에 판단을 맡김 | 씀 |
| `decide` | status 확정, 자동 확정을 막을 신호 확인 | 안 씀 |

조건부 엣지가 이 그래프의 핵심입니다. **이름이 하나로 명확한 기록은 모델을 부르지 않는다**는
결정이 코드가 아니라 그래프 구조로 드러납니다.

검증 그래프는 분기 없이 `perceive → plan → act → reflect` 로 곧게 흐릅니다. 구조적 패턴을
먼저 훑고(`perceive`), 모델에 물은 뒤(`act`), 마지막에 코드가 최종 판정을 내립니다(`reflect`).
**모델 응답과 무관하게 지켜야 하는 규칙은 전부 `reflect` 에 있습니다.**

---

## 고칠 곳

| 무엇을 바꾸고 싶은가 | 파일 |
| --- | --- |
| 임계값 · 동작 켜고 끄기 | [matching/config.py](matching/config.py) — 전부 여기 모여 있습니다 |
| 이름을 찾는 방식 | [matching/names.py](matching/names.py) |
| 판정 규칙 | [matching/nodes.py](matching/nodes.py) |
| 모델에게 주는 지시 | [matching/prompts.py](matching/prompts.py) |
| 입출력 계약 | [matching/schemas.py](matching/schemas.py) |
| 검증 이슈 유형 · 등급 | [validation/config.py](validation/config.py) |
| 검증 판정 규칙 | [validation/nodes.py](validation/nodes.py) |

`config.py` 의 플래그는 전부 되돌릴 수 있게 되어 있습니다. 규칙 하나를 끄고 평가를 돌리면
그 규칙의 기여도를 바로 볼 수 있습니다.

---

## 판정 기준은 어디에 있나

**어떤 상황에서 어떤 status 가 정답인가**는 레포 안에 있습니다.

| 에이전트 | 판정 기준 | 동기화 테스트 |
| --- | --- | --- |
| 매칭 | [matching/CRITERIA.md](matching/CRITERIA.md) | `evals/scripts/matching/test_criteria_sync.py`<br>`evals/scripts/matching/test_auto_gate.py` |
| 검증 | [evals/scripts/validation/README.md](evals/scripts/validation/README.md) | `evals/scripts/validation/test_prompt_sync.py` |

**문서가 기준이고 코드가 그것을 따릅니다.** 둘이 어긋나면 동기화 테스트가 깨집니다.
LLM 을 부르지 않으므로 즉시 끝납니다.

바꿀 때는 **문서를 먼저 고치고 코드를 맞춥니다.** 순서가 반대면 기준이 구현을
따라가게 되고, 그러면 "왜 이렇게 판정하나" 에 답할 수 없습니다.
