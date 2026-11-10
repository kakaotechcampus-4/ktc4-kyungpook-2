# Infra

운영 서버 구성과 배포 자동화입니다. 비밀값은 커밋하지 않고, 환경 변수는 `.env.example`로만 공유합니다.

- `docker/` — Docker Compose (DB · 백엔드 · AI · 프론트 · Caddy)
- `caddy/` — 외부 진입점 · 자동 HTTPS · 보안 헤더
- `iam/` — GitHub Actions 배포 역할의 AWS IAM 정책
- `scripts/` — 배포 · 확인 스크립트와 그 테스트

---

## 운영 구성

```
인터넷 ─→ Caddy (80 · 443, 자동 HTTPS) ─┬─ /api · /oauth2 · /login/oauth2 ─→ backend (127.0.0.1:8080)
                                         └─ 그 밖 ─────────────────────────→ frontend (Nginx 정적 파일)
           backend ─→ ai (127.0.0.1:8000, 밖에 열지 않음) · db (PostgreSQL) · S3 (원본 파일)
```

- 서버는 EC2 한 대이고, 주소는 `https://iitda.duckdns.org`입니다
- 서버의 레포 위치는 `/home/ubuntu/ktc4-kyungpook-2`, 브랜치는 `develop`입니다
- 운영 값은 서버의 `infra/docker/.env`와 `AI/.env`에 있습니다

---

## CI/CD

영역마다 워크플로가 따로입니다. **바뀐 영역만** 검사하고, `develop`에 병합되면 그 영역만 배포합니다.

| 워크플로 | PR에서 | `develop` 병합 뒤 |
| --- | --- | --- |
| [Backend CI/CD](../.github/workflows/backend-ci-cd.yml) | 배포 스크립트 테스트 · `./gradlew test integrationTest build` | 이미지 게시 → 백엔드만 교체 |
| [AI CI/CD](../.github/workflows/ai-ci-cd.yml) | 배포 스크립트 테스트 · 이미지 빌드 · 모델 키 없이 동작 확인 | 이미지 게시 → AI만 교체 |
| [Frontend CI/CD](../.github/workflows/frontend-ci.yml) | 배포 스크립트 테스트 · `typecheck` · `build` | 이미지 게시 → 프론트만 교체 |
| [E2E](../.github/workflows/e2e.yml) | 프론트 · 백엔드가 바뀐 PR에서 Playwright | 없음 (배포와 별개) |

- 각 영역의 `*.md`만 바꾸면 배포하지 않습니다. `AI/evals/`도 이미지에 넣지 않아 배포하지 않습니다
- `infra/docker/compose.yaml`과 `scripts/send-deploy.py`는 세 워크플로가 모두 보고 있어, 바꾸면 셋 다 다시 배포됩니다
- 이미지는 `ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-{backend,ai,frontend}:<커밋 SHA>`로 올리고, 배포에는 digest를 씁니다
- 배포는 AWS SSM Run Command로 서버에서 `scripts/deploy-*.sh`를 실행합니다
- 건너뛴 변경은 다음 배포 때 함께 나갑니다. 바로 내보내려면 **Actions → 해당 워크플로 → Run workflow**에서 `develop`을 고릅니다

### GitHub Variables

저장소 **Settings → Secrets and variables → Actions → Variables**. 비밀값이 아니라 Variables로 둡니다.

| 이름 | 값 | 뜻 |
| --- | --- | --- |
| `AWS_ROLE_ARN` | `arn:aws:iam::782178410739:role/ktc-backend-deploy` | 배포 역할 |
| `AWS_REGION` · `EC2_INSTANCE_ID` | `ap-northeast-2` · `i-0e30a4108bfd4ea9f` | 배포 대상 |
| `PUBLIC_ORIGIN` | `https://iitda.duckdns.org` | 배포 확인에 쓰는 공개 주소 |
| `CADDY_READY` | `true` | 백엔드 · 프론트 자동 배포 스위치. 운영 반영을 잠시 멈추려면 내립니다 |
| `USE_MOCK` · `VITE_AUTH_MOCK` | `false` · `false` | 프론트 번들에 들어가는 mock 스위치 |

**프론트의 `VITE_*` 값은 CI가 빌드할 때 번들에 들어갑니다.** 운영 값은 서버 `.env`가 아니라 이 Variables입니다.
프론트는 `"false"`라는 글자만 실연동으로 읽어서, 비워 두면 mock이 켜진 채 나갑니다. 그래서 게시 전에 두 값이 정확히 `true`/`false`인지 검사합니다.

---

## 배포는 이렇게 돕니다

세 영역이 같은 순서를 따릅니다 ([deploy-backend.sh](scripts/deploy-backend.sh) · [deploy-ai.sh](scripts/deploy-ai.sh) · [deploy-frontend.sh](scripts/deploy-frontend.sh)).

1. **확인** — 다른 배포가 돌고 있으면 최대 5분 기다립니다(서버 잠금 `.git/deploy.lock`). 서버 파일이 수정됐거나 브랜치가 `develop`이 아니면 멈춥니다
2. **checkout 갱신** — 아래 규칙대로 서버 레포를 배포할 커밋으로 옮깁니다
3. **이미지 받기** — 지금 이미지를 복구용 태그로 남기고 새 이미지를 받습니다. 받기에 실패하면 실행 중인 것은 그대로입니다
4. **교체 · 확인** — 그 영역 컨테이너만 바꾸고 확인합니다
   - 백엔드: 상태 확인 · Caddy 설정이 바뀌었으면 재로딩 · CORS · 카카오 콜백 주소 · 외부 HTTPS
   - AI: `/health`와 모델을 부르지 않는 매칭 한 건 · 모델 키가 있는지 ([verify-ai.py](scripts/verify-ai.py))
   - 프론트: 200이고 `index.html` 안에 번들 `<script>`가 있는지 (기본 Nginx 안내 페이지도 200이라)
5. **기록 또는 복구** — 성공하면 서버 `.env`의 `BACKEND_IMAGE` · `AI_IMAGE` · `FRONTEND_IMAGE`에 digest를 적습니다. 실패하면 checkout과 이전 이미지(백엔드는 이전 Caddy 설정까지)로 되돌리고 워크플로를 실패로 남깁니다

컨테이너 하나를 바꾸므로 짧은 중단이 있습니다. **이미지를 되돌려도 이미 실행된 DB 마이그레이션은 되돌아가지 않습니다.**

### 서버 checkout 갱신 규칙

세 배포가 겹치거나 순서가 뒤바뀌어도 옛 이미지와 새 서버 파일이 섞이지 않게, 배포할 커밋 C를 이렇게 판정합니다.

1. C가 `origin/develop`에 없거나, 실행 중인 이미지보다 오래된 커밋이면 실패
2. 서버 HEAD가 C의 조상이면 fast-forward. 실패하면 이 이동을 되돌립니다
3. C가 이미 HEAD에 들어 있으면(다른 영역 배포가 먼저 옮김), 그 사이에 이 영역 파일 변경이 없을 때만 그대로 둡니다
4. 이력이 갈라졌으면 실패

3번의 "이 영역 파일"은 스크립트의 `service_paths`이고 워크플로 `paths`와 같아야 합니다. 테스트가 둘이 같은지 봅니다.

### 실패했을 때

- 실패한 뒤 그 영역을 건드리지 않는 push만 이어지면 다시 배포되지 않습니다. 원인을 고친 뒤 **Run workflow**로 `develop`을 수동 실행합니다
- 서버에서 직접 배포할 때는 같은 스크립트를 씁니다

  ```bash
  cd /home/ubuntu/ktc4-kyungpook-2
  EXPECTED_PUBLIC_ORIGIN=https://iitda.duckdns.org bash infra/scripts/deploy-backend.sh '<커밋 SHA>' \
    'ghcr.io/kakaotechcampus-4/ktc4-kyungpook-2-backend@sha256:<digest>'
  ```

  AI · 프론트는 `deploy-ai.sh` · `deploy-frontend.sh`에 `<커밋 SHA>`와 이미지만 줍니다
- 로그에 `Could not restore the server checkout`이 남으면 서버에서 `git status`와 `git log -1`을 확인합니다
- 외부에서 확인: `python3 infra/scripts/verify-proxy.py health --origin https://iitda.duckdns.org`

---

## DB 마이그레이션

백엔드는 뜰 때 Flyway로 `backend/src/main/resources/db/migration`의 새 파일을 실행하고, 엔티티와 테이블이 맞는지 본 뒤 뜹니다. 맞지 않으면 뜨지 않고 위 5번 복구가 돕니다.
마이그레이션을 쓰는 규칙은 [DB 스키마 §4](../docs/db/schema.md#4-스키마-바꾸기)에 있습니다.

적용 기록은 서버의 `infra/docker`에서 확인합니다.

```bash
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank"'
```

- 되돌리기 어려운 마이그레이션(컬럼 삭제 등)을 배포하기 전에는 DB를 백업합니다: `docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > ~/itda-<날짜>.dump`
- 2026-11-10에 처음 적용했습니다. 기존 운영 DB는 `1`(`<< Flyway Baseline >>`)로 표시하고 `2`부터 실행했습니다
- 로컬 compose에서 예전 볼륨이 엔티티와 달라 백엔드가 뜨지 않으면 `docker compose down -v`로 볼륨을 지우고 다시 띄웁니다 (로컬 데이터가 지워집니다)

---

## Caddy와 HTTPS

- Caddy가 공인 인증서를 발급 · 갱신하고 HTTP를 HTTPS로 넘깁니다. 도메인이 Elastic IP를 가리키고, 보안 그룹이 80 · 443을 열어야 합니다
- **`caddy_data` · `caddy_config` 볼륨은 지우지 않습니다.** 인증서와 발급 계정이 들어 있습니다
- 운영 `.env`: `PUBLIC_ORIGIN=https://iitda.duckdns.org` · `AUTH_COOKIE_SECURE=true`. `PUBLIC_ORIGIN`을 바꾸면 Caddy와 백엔드를 다시 만들어야 하고, GitHub의 `PUBLIC_ORIGIN`도 맞춥니다
- 운영 Swagger(`/swagger-ui*` · `/v3/api-docs*`)는 404로 막습니다. 보려면 `ssh -L 8080:127.0.0.1:8080 ubuntu@<서버>` 뒤 `http://localhost:8080/swagger-ui/index.html`
- API 응답 대기 · 읽기 시간은 각각 120초로 제한합니다

**보안 헤더** — 모든 응답에 붙이고, Caddy가 직접 만드는 오류(502 · 413)에도 붙입니다.

- `Strict-Transport-Security` (1년) · `X-Content-Type-Options: nosniff` · `X-Frame-Options: DENY` · `Referrer-Policy` · `Permissions-Policy` (카메라 · 마이크 · 위치 끔) · `Server` 헤더는 지움
- `Content-Security-Policy` — 화면은 같은 오리진만 허용하고, React Router SPA의 인라인 스크립트 때문에 `'unsafe-inline'`을 둡니다. API는 `default-src 'none'`
- 화면 CSP의 `connect-src`에는 **원본 기록 S3 버킷 주소**가 있습니다. 브라우저가 파일을 S3로 바로 올리기 때문입니다. 버킷을 바꾸면 Caddyfile과 `scripts/tests/test_caddy_proxy.py`의 `FRONTEND_CSP`를 함께 고칩니다. 버킷 CORS(운영 주소의 `PUT`만 허용)는 AWS 콘솔에서 설정합니다
- 화면이 외부 스크립트 · 글꼴 · 이미지를 새로 쓰면 CSP에 그 주소를 더해야 합니다. 안 더하면 브라우저가 막습니다

**압축** — 화면 응답에만 `zstd` · `gzip`을 겁니다 (첫 화면 약 449KB → 135KB). API · OAuth 응답은 비밀값이 본문에 있을 수 있어(BREACH 공격) 압축하지 않습니다.

---

## 처음 한 번 하는 설정

**AWS IAM 역할** — GitHub Actions가 쓰는 `ktc-backend-deploy` 역할의 정책은 [`iam/`](iam)에 있습니다. 이 레포의 `develop`만 믿고, 대상 EC2에 SSM 명령 실행 · 결과 조회만 허용합니다.
레포의 OIDC subject는 조직 · 레포 ID가 들어간 `repo:kakaotechcampus-4@287968646/ktc4-kyungpook-2@1340154210:ref:refs/heads/develop`입니다. 역할을 다시 만들 때는 레포 루트에서 실행합니다.

```bash
aws iam create-role --role-name ktc-backend-deploy --assume-role-policy-document file://infra/iam/backend-deploy-trust.json
```

```bash
aws iam put-role-policy --role-name ktc-backend-deploy --policy-name BackendDeploySSM --policy-document file://infra/iam/backend-deploy-permissions.json
```

**서버의 GHCR 로그인** — 비공개 이미지를 받으려면 서버의 `ubuntu` 계정이 GHCR에 로그인해 있어야 합니다. `read:packages` 권한의 PAT classic을 쓰고, 토큰이 만료되면 다시 로그인합니다. `sudo docker login`은 root 계정에 저장돼 배포에 적용되지 않습니다.

```bash
read -r -p 'GitHub username: ' u; read -r -s -p 'token: ' t; printf '\n'; printf '%s' "$t" | docker login ghcr.io -u "$u" --password-stdin; unset u t
```

---

## 로컬 검증

배포 스크립트 테스트는 임시 Git 레포 · 가짜 Docker · 임시 Caddy로 성공 · 실패 · 복구 경로를 확인합니다. 실제 서버에는 접근하지 않습니다.

```bash
python3 -m unittest discover -s infra/scripts/tests -v
```

- Docker가 없으면 Caddy 프록시 테스트를, `flock`이 없으면(macOS) 잠금 테스트를 건너뜁니다. CI에서는 `REQUIRE_DOCKER_TESTS` · `REQUIRE_FLOCK_TESTS`로 반드시 돌립니다
- AI 이미지를 직접 확인할 때: `docker build --platform linux/amd64 -t ktc-ai:local AI` → `docker run -d --rm --name ai-local -p 127.0.0.1:8000:8000 ktc-ai:local` → `python3 infra/scripts/verify-ai.py --offline`
