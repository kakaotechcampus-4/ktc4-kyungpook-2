# Backend

잇다(ITDA)의 API 서버입니다. 로그인 · 회원가입, 아이와 보호자 연결, 동의, 원본 기록 업로드, 1차 · 2차 검토, 공유를 맡고,
AI 서버를 부르는 워커(매칭 · 검증 · 요약 · 인사이트 · 알림)가 이 안에서 돕니다.

- 스택: Java 21 · Spring Boot 3.5 · Spring Security(카카오 OAuth2 + JWT 쿠키) · Spring Data JPA · PostgreSQL · Flyway · Springdoc
- 테스트: JUnit 5 · H2(빠른 테스트) · Testcontainers PostgreSQL(통합 테스트)

---

## 실행

JDK 21이 필요합니다. Gradle도 JDK 21로 실행해야 해서, 여러 JDK가 있으면 먼저 맞춥니다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### 로컬 PostgreSQL로 실행

1. `src/main/resources/application-local.yml`에 DB 접속 정보를 둡니다 (Git에서 무시됩니다).

   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/itda
       username: your_username
       password: your_password
     jpa:
       hibernate:
         ddl-auto: update
   ```

2. JWT 서명 키와 카카오 앱 키 파일을 만들고 값을 채웁니다 (Git에서 무시됩니다).

   ```bash
   cp src/main/resources/application-secret.example.yml src/main/resources/application-secret.yml
   ```

3. 실행합니다. **http://localhost:8080** 에서 뜹니다.

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=local'
   ```

로컬 프로필은 Flyway를 끄고 Hibernate가 테이블을 만듭니다. 운영과 같은 방식은 Docker Compose로 띄울 때입니다.

### Docker Compose로 실행

전체 스택(DB · 백엔드 · AI · 프론트 · Caddy)을 함께 띄웁니다. 방법은 [루트 README](../README.md)에 있습니다.

- `docker` 프로필로 뜨고, 기동할 때 Flyway가 스키마를 만들거나 바꿉니다
- 백엔드 8080은 `127.0.0.1`에만 열립니다

### 카카오 계정 없이 테스트하기 (개발용 로그인)

카카오 계정 하나는 회원 하나이고 역할도 바꿀 수 없어서, 기관과 보호자를 오가는 흐름은 개발용 로그인으로 확인합니다.

1. 백엔드를 `DEV_LOGIN_ENABLED=true`로 실행하고, 프론트는 `VITE_AUTH_MOCK=false` · `VITE_USE_MOCK=false`로 띄웁니다
2. 브라우저에서 `http://localhost:3000/api/v1/dev/login?as=org1`을 열면 `dev:org1` 테스트 회원으로 들어갑니다
3. `?as=parent1`로 바꾸면 보호자 테스트 회원으로 바뀝니다. 두 계정을 동시에 띄우려면 시크릿 창을 함께 씁니다

`as`는 영문 소문자 · 숫자 · 하이픈 1~20자이고, 새 이름이면 새 회원을 만듭니다. 꺼져 있으면 이 주소는 404입니다.
배포 서버는 Compose가 켜 두므로, 정식 오픈 전에 서버 `infra/docker/.env`에 `DEV_LOGIN_ENABLED=false`를 넣어 끕니다.

---

## 테스트와 빌드

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```

```bash
./gradlew build
```

- `test` · `build`는 H2로 돌아 Docker가 필요 없습니다
- `integrationTest`는 Docker가 켜져 있어야 합니다. `@Tag("integration")`이 붙은 Testcontainers 테스트만 돕니다
- 엔티티를 바꿨다면 `FlywayMigrationIntegrationTest`가 마이그레이션과 엔티티가 맞는지 봅니다

---

## API 문서

백엔드를 띄운 뒤 Swagger UI를 엽니다.

- `http://localhost:8080/swagger-ui/index.html`
- OpenAPI: `/v3/api-docs` (JSON) · `/v3/api-docs.yaml`

운영에서는 Caddy가 Swagger를 막습니다. 서버에서는 SSH 터널(`ssh -L 8080:127.0.0.1:8080 …`)로 붙습니다.
API 응답 형식과 오류 코드 규약은 [api-conventions.md](../docs/api/api-conventions.md)에 있습니다.

---

## 로그인

- 기관과 보호자 모두 `/oauth2/authorization/kakao`로 로그인합니다. 처음 로그인하면 역할이 없는 회원이 생기고, `POST /api/v1/auth/signup`에서 역할을 정합니다. 역할은 바꿀 수 없습니다
- 기관은 가입할 때 기관명 · 유형 · 사업자등록번호(형식만 검사)를 함께 보내고, 이때 기관이 만들어집니다
- 로그인 상태는 httpOnly 쿠키의 JWT입니다. 3시간짜리이고 쓰는 동안 자동으로 늘어나며, 처음 로그인부터 12시간에 끝납니다
- 쓰기 요청은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 합니다

---

## DB 스키마

운영 DB 스키마는 Flyway 마이그레이션(`src/main/resources/db/migration`)만 바꿉니다. `docker` 프로필은 `ddl-auto: validate`라 엔티티와 테이블이 다르면 뜨지 않습니다.

- 스키마를 바꾸는 규칙: [DB 스키마 §11.4](../docs/db/schema.md)
- 배포 절차: [인프라 문서](../infra/README.md#db-마이그레이션)
- 서버 DB를 직접 고치는 도구(테스트 아이 넣기 · 멈춘 일지 되돌리기 등): [scripts/README.md](scripts/README.md)

---

## 설정 프로필

- 기본 (`application.yml`) — 공통 설정. Flyway 꺼짐
- `local` (`application-local.yml`, Git 무시) — 개발자별 로컬 DB
- `docker` (`application-docker.yml`) — Compose · 운영. Flyway 켜짐, S3 저장
- `test` (`src/test/resources/application-test.yml`) — H2 테스트
- 항상 읽음 (`application-secret.yml`, Git 무시) — JWT 서명 키 · 카카오 앱 키

---

## 환경 변수

**꼭 필요한 것**

- `JWT_SECRET` — JWT 서명 키. 32바이트 이상, 없으면 뜨지 않습니다
- `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` — 카카오 REST API 키와 시크릿
- `docker` 프로필: `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `RAW_STORAGE_S3_BUCKET` · `CORS_ALLOWED_ORIGINS` · `AUTH_SUCCESS_REDIRECT` · `AUTH_FAILURE_REDIRECT`

**로그인 · 탈퇴**

- `AUTH_COOKIE_SECURE` (`false`) — HTTPS면 `true`
- `DEV_LOGIN_ENABLED` (`false`, Compose는 `true`) — 개발용 로그인
- `WITHDRAWAL_ENABLED` (`false`) — 회원 탈퇴를 열지. 켜기 전에 `KAKAO_ADMIN_KEY`와 카카오 연결 해제 웹훅을 준비합니다
- `KAKAO_ADMIN_KEY` · `KAKAO_APP_ID` — 탈퇴할 때 카카오 연결 해제와 웹훅 검증에 씁니다

**저장 · 한도**

- `RAW_STORAGE_DIR` (`./data/raw-storage`) — 기본 프로필의 원본 파일 위치. `docker` 프로필은 S3(`AWS_REGION`, 기본 `ap-northeast-2`)
- `RAW_RECORD_MAX_ENTRIES_PER_FILE` (`500`) — 파일 하나에서 나올 수 있는 기록 수
- `RAW_RECORD_UPLOAD_MAX_CONCURRENT` (`2`) · `RAW_RECORD_UPLOAD_MAX_WAITING` (`4`) — 업로드를 동시에 처리하는 수와 기다릴 수 있는 수
- `AGENT_QUEUE_MAX_AWAITING_ENTRIES` (`1000`) — 기관마다 AI 처리 줄에 세워 둘 수 있는 기록 수

**AI · 워커**

- `AI_BASE_URL` (`http://localhost:8000`) — AI 서버 주소
- `AI_PROGRESS_ENABLED` (`false`, Compose는 `true`) — 대시보드에 AI 진행 단계 표시
- `SUMMARY_AUTO_RUN` (`true`) — 끄면 선생님이 [요약]을 누른 묶음만 요약합니다
- `INSIGHT_INCLUDE_CURRENT_WEEK` (`false`) — 시연용. 끝나지 않은 이번 주도 인사이트 재료로 봅니다
- 워커 스위치 (모두 기본 `true`): `MATCHING_WORKER_ENABLED` · `VALIDATION_WORKER_ENABLED` · `SUMMARY_WORKER_ENABLED` · `INSIGHT_WORKER_ENABLED` · `NOTIFY_WORKER_ENABLED` · `PURGE_WORKER_ENABLED` · `UPLOAD_SWEEPER_ENABLED` · `WITHDRAWAL_WORKER_ENABLED`

---

## 관련 문서

- 구현 · 검토 규칙: [AGENTS.md](AGENTS.md)
- API 규약: [api-conventions.md](../docs/api/api-conventions.md)
- 배포와 서버 설정: [인프라 문서](../infra/README.md)
