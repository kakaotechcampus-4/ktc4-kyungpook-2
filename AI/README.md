# AI

관찰 기록 파이프라인의 에이전트와 평가 도구를 관리합니다.

```
Local Record → ①매칭 → ②검증 → ③요약 → [Gate 1] → ④인사이트 → [Gate 2] → ⑤공유
                 ↑
            현재 구현 완료
```

| 디렉터리 | 내용 |
| --- | --- |
| `matching/` | 매칭 에이전트 — 기록 한 줄이 어느 아동의 것인지 판정 |
| `evals/` | 평가 스크립트 — 테스트 데이터 실행과 채점 ([README](evals/README.md)) |
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

> 서버에서는 `127.0.0.1:8000` 에만 바인드되고 nginx 가 라우팅하지 않습니다.
> 인증이 없는 엔드포인트라 외부에 열지 않습니다 — 확인하려면 서버 안에서 `curl localhost:8000` 을 씁니다.

---

## API

| 메서드 | 경로 | 역할 |
| --- | --- | --- |
| `GET` | `/health` | 상태 확인. `luna_configured` 로 키 설정 여부를 함께 알려줍니다 |
| `POST` | `/matching` | 기록 한 줄을 받아 판정 하나를 돌려줍니다 |
| `GET` | `/llm-test` | Luna 연결 확인용 |

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

## 그래프 구조

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

---

## 고칠 곳

| 무엇을 바꾸고 싶은가 | 파일 |
| --- | --- |
| 임계값 · 동작 켜고 끄기 | [matching/config.py](matching/config.py) — 전부 여기 모여 있습니다 |
| 이름을 찾는 방식 | [matching/names.py](matching/names.py) |
| 판정 규칙 | [matching/nodes.py](matching/nodes.py) |
| 모델에게 주는 지시 | [matching/prompts.py](matching/prompts.py) |
| 입출력 계약 | [matching/schemas.py](matching/schemas.py) |

`config.py` 의 플래그는 전부 되돌릴 수 있게 되어 있습니다. 규칙 하나를 끄고 평가를 돌리면
그 규칙의 기여도를 바로 볼 수 있습니다.

---

## 판정 기준은 어디에 있나

**어떤 상황에서 어떤 status 가 정답인가**는 노션 문서에서 관리합니다.

→ [AI · Matching Agent 자동 확정 판정 기준](https://app.notion.com/p/elice-track/AI-Matching-Agent-9-23-3e42bb98425780498d9ffb9db2d83819?v=e022bb98425783e2baac88bd5a3d8489&source=copy_link)

구현이 아니라 **정책**이라 레포가 아닌 노션에 둡니다. 테스트 데이터를 만들 때 이 문서를 보고
정답을 정하며, 구현이 바뀌어도 원칙은 바뀌지 않습니다.

## CI/CD

`.github/workflows/ai-ci-cd.yml`은 `develop`·`main` 대상 PR에서 Python 3.11의 의존성
검사(`pip check`), pytest, Docker 빌드와 `/health` 상태 확인을 실행합니다.
`tests/`는 입력 검증·이름 매칭·모킹한 LLM 응답과 실패를 검증하며 운영 키나 실제 Luna 호출을 사용하지 않습니다.
모델을 호출하는 `evals/` 평가 스크립트는 CI에서 실행하지 않습니다. 테스트 보고서 보관 기간은 7일입니다.

모든 `develop` push에서 `ai-develop`의 마지막 성공 배포 SHA부터 누적 변경을 비교합니다.
AI 또는 공통 배포 설정 변경이 있으면 검증 후 GHCR 이미지를 게시하고 기존 EC2의 AI만 교체합니다.
수동 실행은 Actions → AI CI/CD → Run workflow에서 `develop`을 선택하면 강제 재배포합니다.
FE 배포와 mock 설정은 이 워크플로의 대상에 포함하지 않습니다.

Compose의 `AI_IMAGE`가 비어 있으면 기존 `ktc-ai`를 사용합니다. 자동 배포는 SHA 태그로
게시한 이미지의 digest를 사용하고, 상태와 revision 검증 후 서버 `infra/docker/.env`에 저장합니다.
`AI/.env`와 평가 데이터 마운트는 유지하며 실패 시 이전 AI 이미지로 복구합니다.
서버의 GHCR 로그인 계정에는 새 AI 패키지의 읽기 접근 권한도 필요합니다.

로컬 테스트는 AI 디렉터리에서 다음과 같이 실행합니다.

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python -m pip check
python -m pytest tests -v
```

AWS·GitHub 설정, 동시 배포 잠금, 성공 기록 및 최초 적용 절차는 [인프라 문서](../infra/README.md#beai-cicd)를 참고하세요.
