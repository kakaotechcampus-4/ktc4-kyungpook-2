# AI

관찰일지를 아이별로 나누고, 걸러 내고, 요약하고, 패턴을 찾아 보호자에게 보내는 에이전트 다섯과 그 평가 도구입니다.
FastAPI 서버 하나에 LangGraph 그래프 다섯이 들어 있고, 백엔드만 이 서버를 부릅니다.

- AI는 DB를 만지지 않습니다. 저장과 상태 관리는 전부 백엔드가 합니다
- 에이전트는 상태를 갖지 않습니다. 같은 요청이 다시 와도 안전합니다
- 모델이 틀려도 마지막에 코드가 다시 판정합니다. 꼭 지켜야 하는 규칙은 프롬프트가 아니라 코드에 둡니다

---

## 한눈에

```
업로드 → 매칭 → 검증 → 요약 → [1차 검토] → 인사이트 → [2차 검토] → 알림
```

사람이 끼어드는 자리는 1차 · 2차 검토 두 곳입니다. 매칭 · 검증 · 요약 · 인사이트 · 알림은 백엔드 워커가 스스로 일을 집어 AI 서버에 보냅니다.

---

## 에이전트

### 매칭 — 이 기록은 어느 아이의 것인가

일지 한 줄과 기관의 아이 명부를 받아 주인을 판정합니다.

- 이름이 그대로 적힌 아이가 한 명뿐이면 모델을 부르지 않습니다. 실제 기록의 대부분이 여기에 해당합니다
- 애매할 때만 모델에 묻습니다. 이 갈림길이 코드가 아니라 그래프 구조로 드러납니다
- 판정은 넷입니다 — `auto` 바로 다음 단계로 · `review` 선생님이 확인 · `multi` 후보 중에서 고름 · `unmatched` 이름으로 직접 찾음

코드 [`matching/graph.py`](matching/graph.py) · 기준 [`matching/CRITERIA.md`](matching/CRITERIA.md)

### 검증 — 그대로 공유해도 안전한가

`PASS` · `REVIEW` · `BLOCK` 중 하나로 판정합니다. `BLOCK`은 요약으로 넘어가지 않습니다.

- 전화번호 · 주민번호는 모델 없이 항상 막습니다. 모델 호출이 실패해도 마찬가지입니다
- 모델이 틀려도 마지막에 코드가 다시 판정합니다. 아이 자신의 이름이나 "연락처로 알렸다"처럼 값 없는 언급을 개인정보로 본 판정은 버립니다
- 누구의 기록인지 모르면 항상 선생님 확인(`REVIEW`)으로 보냅니다

코드 [`validation/nodes.py`](validation/nodes.py) · [`validation/config.py`](validation/config.py) · 기준 [`evals/scripts/validation/README.md`](evals/scripts/validation/README.md)

### 요약 — 하루치 일지를 한 편으로

같은 아이 · 같은 날 · 같은 기관의 일지 여러 건을 한 편의 요약으로 묶습니다.

- 모델이 쓴 문장마다 원문 근거를 대조해, 일지에 없는 말은 버립니다
- 쓸 관찰 사실이 없으면 빈 요약을 돌려줍니다

코드 [`summary/nodes.py`](summary/nodes.py) · 기준 [`summary/CRITERIA.md`](summary/CRITERIA.md)

### 인사이트 — 여러 기관에 걸친 패턴 찾기

한 아이의 승인된 요약을 겹쳐 기관을 가로지르는 돌봄 패턴을 찾습니다.

- 보호자가 동의하지 않은 기관의 기록은 처음부터 뺍니다
- 근거가 약한 인사이트는 한 번만 고쳐 쓰게 합니다. 한 기관에서만 보인 패턴은 도구로 지난 기록을 찾아 보강합니다
- 고친 것도 검사를 처음부터 다시 통과해야 남습니다. 못 통과하면 그 인사이트만 버립니다
- 고쳐 쓰기와 도구는 환경변수로 켭니다. 기본은 꺼져 있습니다

코드 [`insight/nodes.py`](insight/nodes.py) · [`insight/tools.py`](insight/tools.py) · 기준 [`insight/CRITERIA.md`](insight/CRITERIA.md)

### 알림 — 승인된 인사이트를 보호자에게

2차 검토에서 승인된 인사이트를 메일로 보냅니다. 아이 정보가 밖으로 나가는 유일한 길입니다.

- 모델을 부르지 않습니다. 선생님이 승인한 문장을 글자 그대로 보냅니다
- 다른 아이 이름이 섞이면 보내지 않습니다
- 메일은 MCP 서버가 보냅니다. 받는 주소는 서버가 직접 찾고, 메일 서버 설정이 없으면 파일로 적습니다

코드 [`notify/graph.py`](notify/graph.py) · [`mcp_servers/itda_notify.py`](mcp_servers/itda_notify.py) · 기준 [`notify/CRITERIA.md`](notify/CRITERIA.md)

---

## 폴더

- `matching/` · `validation/` · `summary/` · `insight/` · `notify/` — 에이전트 하나씩
- `common/` — 모델 접속 · 실패 종류 · 진행 현황 · 호출 수
- `mcp_servers/` — 알림 메일을 보내는 MCP 서버
- `evals/` — 테스트 · 평가 · 측정 기록 (이미지에 넣지 않습니다)
- `main.py` — FastAPI 서버

---

## 실행

```bash
cp .env.example .env
```

```bash
pip install -r requirements.txt
```

```bash
uvicorn main:app --reload --port 8000
```

도커로 띄울 때는 레포 루트에서 실행합니다.

```bash
docker compose -f infra/docker/compose.yaml up -d --build ai
```

`.env`에는 모델 키와 에이전트별 모델 주소를 넣습니다. 각 값의 뜻은 [`.env.example`](.env.example)에 적혀 있습니다.
운영에서는 매칭 · 검증에 `gpt-6-luna`, 요약 · 인사이트에 `claude-sonnet-5-5`를 씁니다 ([고른 이유](evals/scripts/real/MODEL_COMPARE.md)).

### 배포

`develop`에 병합되면 [AI CI/CD](../.github/workflows/ai-ci-cd.yml)가 서버의 AI 컨테이너만 바꿉니다. 문서와 `evals/`만 바꾸면 다시 배포하지 않습니다.
AI 서버는 인증이 없어서 서버 안에서만 열립니다. 자세한 절차는 [인프라 문서](../infra/README.md#ai-cicd)에 있습니다.

---

## API

| 경로 | 하는 일 |
| --- | --- |
| `POST /matching` · `/validation` · `/summary` · `/insight` · `/notify` | 에이전트 다섯. 입출력은 각 폴더의 `schemas.py`가 기준입니다 |
| `GET /health` | 검증 에이전트의 모델 설정이 있으면 200, 없으면 503. 백엔드가 AI 장애를 판정할 때 씁니다 |
| `GET /progress` | 지금 어느 에이전트가 어느 단계에 있는지. 대시보드 진행 현황에 씁니다 |
| `GET /metrics` | 요청마다 몇 번 불렸는지. 재시도를 확인할 때 씁니다 |

실패는 모두 503이고, 백엔드는 `detail.reason`을 보고 다음 행동을 정합니다.

- `llm_unavailable` 모델 호출 실패 → 멈췄다가 나중에 다시
- `no_grounded_claims` 요약 문장이 전부 근거 대조에서 떨어짐 → 그 묶음만 되돌림
- `delivery_failed` 메일 서버 장애 → 다시 보냄

---

## 테스트

모델 응답을 고정해 두고 코드가 지키는 규칙만 봅니다. 모델을 부르지 않아 바로 끝납니다.

```bash
for t in evals/scripts/*/test_*.py; do python "$t" > /dev/null || echo "FAIL $t"; done
```

아무것도 출력되지 않으면 전부 통과입니다. 에이전트별 테스트는 `evals/scripts/<에이전트>/test_*.py`에 있습니다.

**판정 기준 문서가 먼저고 코드가 따릅니다.** 둘이 어긋나면 동기화 테스트가 깨집니다.
바꿀 때는 문서를 먼저 고치고 코드를 맞춥니다.

---

## 평가

실제 모델로 평가 세트를 돌려 점수를 잽니다. `.env`에 모델 키가 있어야 합니다.

- **지금 점수** — [evals/SCOREBOARD.md](evals/SCOREBOARD.md)
- **측정할 때마다 무엇이 틀렸고 무엇을 고쳤나** — [evals/MEASUREMENTS.md](evals/MEASUREMENTS.md)
- **에이전트별 평가 방법** — [매칭](evals/scripts/matching/README.md) · [검증](evals/scripts/validation/README.md) · [요약](evals/scripts/summary/README.md) · [실데이터 전체 흐름](evals/scripts/real/README.md)
- **선생님이 AI 초안을 얼마나 고치나** — [evals/scripts/feedback/README.md](evals/scripts/feedback/README.md)

평가 데이터와 결과는 `evals/generated/`에 생기고 커밋하지 않습니다.

---

## 고칠 곳

| 바꾸고 싶은 것 | 파일 |
| --- | --- |
| 임계값 · 기능 켜고 끄기 | `<에이전트>/config.py` |
| 판정 규칙 | `<에이전트>/nodes.py` |
| 모델에게 주는 지시 | `<에이전트>/prompts.py` |
| 입출력 | `<에이전트>/schemas.py` (백엔드의 `*AgentClient`도 함께) |
| 단계 이름 | `<에이전트>/graph.py` (백엔드 진행 현황 문구와 `test_progress.py`도 함께) |
| 모델 접속 | `common/luna.py` |

설계 이유와 약한 점은 [AGENTIC.md](AGENTIC.md)에 있습니다.
