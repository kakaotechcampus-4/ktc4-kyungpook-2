# Backend

잇다(ITDA)의 API 서버입니다. 기관과 학부모가 이용하는 서비스의 도메인 로직,
데이터베이스 연동, 인증·인가를 담당합니다.

## 현재 상태

기본 Spring Boot 애플리케이션, 데이터베이스 연결 설정, Swagger/OpenAPI 문서 기반과
함께 공통 응답 래퍼, 전역 예외 처리, 카카오 로그인(Spring Security `oauth2Login`),
JWT 쿠키 인증과 CSRF 보호, 회원가입이 구현되어 있습니다. 로그인한 사용자는 `User`로
저장되고, 기관 담당자는 가입할 때 만든 `Organization`에 소속됩니다.

도메인 API는 원본 기록 업로드·조회(`/api/v1/raw-records`)가 있으며, 원본 파일은 기본
프로필에서 로컬 디스크에, `docker` 프로필에서 S3에 저장합니다. 원본 기록은 로그인한
사용자의 소속 기관 기준으로 저장·조회됩니다.

### 로그인과 회원가입

기관 담당자와 보호자 모두 `GET /oauth2/authorization/kakao`로 로그인합니다. 로그인은 신원 확인만
하고 역할을 정하지 않습니다.

1. 처음 로그인하면 역할 없는(`role = NULL`) **가입 미완료** 회원이 만들어집니다.
2. 프론트는 `GET /api/v1/auth/me`의 `signupCompleted: false`를 보고 역할 선택 화면을 띄웁니다.
3. `POST /api/v1/auth/signup`으로 역할을 확정합니다. 보호자는 `role`만, 기관은 기관명·기관유형·
   사업자등록번호를 함께 보내고, 이때 `organization` 행이 새로 만들어집니다(기관 1곳당 계정 1개).

사업자등록번호는 숫자 10자리 형식만 검사하고 진위·체크섬은 검증하지 않습니다. 역할은 한 번 정해지면
바뀌지 않습니다. 가입 미완료 회원은 `/auth/me` · `/auth/signup` · `/auth/logout` 외의 API에서
`403 SIGNUP_NOT_COMPLETED`로 막힙니다. 요청·응답 형식은
[API 명세 §2.2 · §2.6](../docs/api/api-spec.md)을 참고하세요.

시연용 기관 시드는 없습니다. 기관은 회원가입으로만 만들어집니다.

없는 회원·탈퇴 회원을 가리키는 JWT는 보호 API 필터에서 `401 SESSION_USER_NOT_FOUND`로 차단합니다.
가입 상태 조회 중 DB 장애 등 서버 오류는 필터에서 `500 INTERNAL_SERVER_ERROR` JSON으로 응답하며
인증 쿠키를 만료시키지 않습니다. 비로그인 API GET 요청은 RequestCache에 저장하지 않아 세션을
생성하지 않고, 카카오 인가 요청의 state를 보관할 때는 세션을 사용합니다.
동시 최초 로그인은 카카오 ID 중복 경합 후 새 트랜잭션에서 먼저 생성된 회원을 조회하므로 두 요청이
같은 회원 ID를 사용합니다. 로그인 시 복구·닉네임 갱신과 회원가입은 회원 행의 잠금으로 조율합니다.

> **기존 배포 데이터를 보존할 때 테이블을 삭제하지 마세요.** `docker` 프로필의 `ddl-auto: update`는
> 이전 실험 스키마의 `users.role` NOT NULL이나 `organization.name` UNIQUE 제약을 제거하지 못합니다.
> 기존 `organization` 행이 있다면 사업자등록번호도 실제 값으로 보완해야 합니다. 해당 스키마가 남아 있는
> 환경은 백업 후 별도의 스키마 변경을 먼저 준비해야 하며, 아래 도구는 소유자 데이터 이전만 담당합니다.

### 기존 원본 기록의 기관 소유자 이전

이전 버전은 `raw_record.institution_id`에 카카오 ID를 저장했습니다. 새 버전은 가입 시 만든 기관 ID를
저장하므로 기존 기록은 명시적 매핑을 통해 옮겨야 합니다. 카카오 ID와 기관 ID가 같은 숫자일 수 있어
값만 보고 자동 추정하지 않습니다. PostgreSQL 16의 `psql`과 아래 수동 도구를 사용합니다.

1. 회원·기관·원본 기록을 포함한 DB 전체를 `pg_dump`로 백업하고, 원본 파일도 보존합니다. 이전이 끝날
   때까지 원본 기록 업로드·조회와 회원 소속 변경을 중단합니다. 이미 숫자가 겹치는 기록이 다른 기관에
   보이는 일을 방지하려면 조회도 차단해야 합니다.
2. 기존 기록 소유자가 새 버전에서 카카오 로그인과 기관 가입을 완료하도록 합니다. 전환 중 로그인·가입은
   허용하되 원본 기록 API는 열지 않습니다. JWT subject가 카카오 ID에서 내부 ID로 바뀌므로 최초 배포 시
   `JWT_SECRET`도 새 키로 교체하여 이전 토큰을 폐기합니다.
3. 백업한 기존 기록 **전체**의 ID·카카오 ID를 새 기관 ID에 연결합니다. 새 버전으로 이미 생성한 기록이
   섞인 DB용 도구가 아니며, 그런 환경에서는 별도로 이전 대상을 분리하는 절차가 필요합니다.
   [CSV 예제](scripts/raw-record-owner-mapping.csv.example)를 복사해 실제 값으로 채웁니다.
   헤더는 `raw_record_id,legacy_kakao_id,organization_id` 순서 그대로 사용합니다.
4. CSV를 보관할 작업 디렉터리에서 아래 검증 명령을 실행합니다. `DATABASE_URL`은 운영자가 준비한
   PostgreSQL 접속 문자열이며, 비밀번호는 명령에 직접 넣지 말고 `.pgpass` 등으로 제공합니다.

```bash
# 이 디렉터리의 raw-record-owner-mapping.csv 를 읽습니다.
# /absolute/path/to/backend 는 실제 저장소의 backend 절대 경로로 바꿉니다.
psql "$DATABASE_URL" -f /absolute/path/to/backend/scripts/migrate-raw-record-owners.sql
```

기본 모드는 이전 예정 내역과 건수를 출력한 뒤 `ROLLBACK`합니다. 빈 매핑, 기록 ID 중복·누락,
소유자 불일치, 없는 기록·기관, 비활성 회원이나 다른 기관 소속은 오류로 중단됩니다. 검증 중에도 테이블
잠금을 사용하므로 점검 시간에 실행하세요. 매핑 누락을 정확히 판정하도록 DB의 원본 기록 전체가 CSV에
포함되어야 합니다.

5. 검증 결과를 백업·CSV와 대조한 뒤 같은 디렉터리에서 적용합니다.

```bash
psql "$DATABASE_URL" -v apply=true -f /absolute/path/to/backend/scripts/migrate-raw-record-owners.sql
```

적용은 하나의 트랜잭션에서 `institution_id`만 갱신합니다. 기록 ID·파일 경로·파일 내용·생성 시각은
변경하지 않습니다. 오류가 나면 연결 종료 시 전체 롤백되므로 일부만 이전되지 않습니다. CSV와 실행
결과는 보관하고, 적용 후 같은 CSV를 반복 실행하지 마세요.

6. 기관별 기존 기록 목록·상세 조회와 다른 기관의 접근 거부를 확인한 뒤 원본 기록 API를 다시 엽니다.
   적용 후 문제가 확인되면 API를 계속 중단한 상태에서 사전 백업으로 복구하고 매핑을 다시 검증합니다.
   도구는 서버 시작 시 자동 실행되지 않습니다.

## 기술 스택

| 항목 | 사용 기술 |
| --- | --- |
| 언어·런타임 | Java 21 |
| 프레임워크 | Spring Boot 3.5.3 |
| 웹·검증 | Spring Web, Bean Validation |
| 인증 | Spring Security, OAuth2 Client(카카오), JJWT 0.12.6 |
| 데이터 접근 | Spring Data JPA |
| 파일 저장 | 로컬 디스크(기본), AWS SDK S3(`docker` 프로필) |
| API 문서 | Springdoc OpenAPI 2.9.1, Swagger UI |
| 운영 DB | PostgreSQL |
| 테스트 DB | H2 (빠른 테스트), Testcontainers PostgreSQL (통합 테스트) |
| 빌드 | Gradle Wrapper |

## 시작하기

### 사전 조건

- JDK 21
- Docker Desktop (Docker 환경으로 실행할 경우)
- 로컬 실행 시 PostgreSQL

Gradle Wrapper 자체도 JDK 21로 실행해야 합니다. `build.gradle`의 Java toolchain은
컴파일·테스트에 사용할 Java 버전을 지정할 뿐, 이미 시작된 Gradle의 런타임을 바꾸지 않습니다.
여러 JDK가 설치된 환경에서는 검증 전에 `java -version`이 21을 가리키는지 확인하세요.

### 테스트와 빌드

`backend/`에서 실행합니다.

```bash
./gradlew test
./gradlew build
```

Windows에서는 `gradlew.bat test`와 `gradlew.bat build`를 사용합니다.

PostgreSQL 동작을 확인하는 Testcontainers 통합 테스트는 Docker Desktop을 실행한 뒤 별도로
실행합니다.

```bash
./gradlew integrationTest
```

### API 문서

애플리케이션을 실행한 뒤 아래 경로에서 API 문서를 확인할 수 있습니다.

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`

새 외부 API를 추가할 때는 컨트롤러의 `@Tag`, `@Operation`과 요청·응답·오류 응답
명세를 같은 변경에서 갱신한다. 인증이 필요한 API에는 `access_token` 쿠키 보안 스킴인
`cookieAuth`(`OpenApiConfig.COOKIE_AUTH_SCHEME`) 보안 요구 사항을 선언하고, 공개 API에는
이를 적용하지 않는다.

### 테스트 환경

- [BackendApplicationTests](src/test/java/com/itda/backend/BackendApplicationTests.java)는
  H2 PostgreSQL 호환 모드로 실행되는 빠른 스모크 테스트다. 단위 테스트와 간단한
  애플리케이션 기동 검증에는 Docker가 필요 없다.
- `@Tag("integration")`이 붙은 통합 테스트는 Testcontainers로 실제 PostgreSQL
  컨테이너를 실행하며 `./gradlew integrationTest`로만 실행한다. JPA 매핑, 쿼리,
  트랜잭션처럼 PostgreSQL 동작을 검증해야 할 경우에만 추가한다.
- Testcontainers 통합 테스트를 실행하려면 Docker Desktop이 실행 중이어야 한다.
  Docker를 사용할 수 없는 환경에서도 `./gradlew test`와 `./gradlew build`는 실행할 수 있다.

프로젝트는 Java 21을 기준으로 한다. 여러 JDK가 설치된 macOS 환경에서는 아래처럼
Java 21을 명시하고 확인한 뒤 테스트·빌드를 실행한다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
java -version # 21.x인지 확인
./gradlew test --rerun-tasks
./gradlew build --rerun-tasks
# Docker Desktop 실행 후 PostgreSQL 통합 테스트가 필요한 경우
./gradlew integrationTest --rerun-tasks
```

### 로컬 PostgreSQL로 실행

기본 `application.yml`에는 데이터베이스 접속 정보가 없습니다. 개인별 설정은
`src/main/resources/application-local.yml`에만 두며, 이 파일은 Git에서 무시됩니다.

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

아래 명령으로 `local` 프로필을 활성화합니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

JWT 서명 키와 카카오 앱 키는 `application.yml`이 자동으로 읽는
`src/main/resources/application-secret.yml`에 둡니다. 예시 파일을 복사해 값을 채웁니다.
이 파일도 Git에서 무시됩니다.

```bash
cp src/main/resources/application-secret.yml.example src/main/resources/application-secret.yml
```

비밀번호, 카카오 클라이언트 시크릿, 운영 키는 Git이 추적하는 `application*.yml`에 커밋하지
않습니다. 환경 변수 또는 무시되는 `application-local.yml`·`application-secret.yml`로만 제공합니다.

### Docker Compose로 실행

루트의 `infra/docker/.env.example`을 참고해 `infra/docker/.env`를 만든 후 실행합니다.

```bash
cd ../infra/docker
docker compose up --build
```

Compose 환경에서는 `docker` 프로필이 활성화되고, `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD` 환경 변수로 PostgreSQL에 연결합니다. `JWT_SECRET`, `KAKAO_CLIENT_ID`,
`KAKAO_CLIENT_SECRET`, `RAW_STORAGE_S3_BUCKET`이 비어 있으면 Compose가 실행을 거부합니다.

외부 진입점은 Caddy입니다. 기본값 `PUBLIC_ORIGIN=http://localhost`에서는 HTTP,
운영 HTTPS 주소를 설정하면 인증서 발급·갱신과 HTTP 리다이렉트를 자동 처리합니다.
백엔드 8080은 `127.0.0.1`에만 연결됩니다. Swagger는 같은 오리진의
`/swagger-ui/index.html`, OpenAPI JSON은 `/v3/api-docs`로 접근합니다.
Compose는 CORS·로그인 완료 주소를 `PUBLIC_ORIGIN`에서 생성합니다.

### 백엔드 CI/CD

`backend/`·`infra/`·워크플로 파일을 바꾼 `develop`·`main` 대상 PR에서는 Java 21로 단위 테스트,
Testcontainers PostgreSQL 통합 테스트, 빌드를 실행합니다(`*.md`나 AI 전용 배포 파일만 바꾸면 실행하지 않습니다).
같은 경로를 바꾼 `develop` push 및 `develop`에서의 수동 실행은 검증 성공 후
Docker 이미지를 GHCR에 올리고, SSM을 통해 기존 EC2의 BE 컨테이너만 배포합니다.
Caddy 재로딩과 상태·CORS·카카오 콜백 검증이 실패하면 이전 BE 이미지와 활성 프록시 설정으로 복구합니다.
실제 EC2 전환이 끝난 뒤 `CADDY_READY=true`를 설정해야 자동 배포가 실행됩니다.

`BACKEND_IMAGE`는 Compose에서 사용할 이미지 참조입니다. 비어 있으면 로컬 빌드용
`ktc-backend`를 사용하고, CD는 성공한 이미지 digest를 서버 `.env`에 기록합니다.
DB 컨테이너·볼륨은 재생성하지 않으며 `ddl-auto: update`를 유지합니다.
이미지 복구는 DB 스키마 변경을 되돌리지 않습니다.

GitHub Variables, GHCR 로그인, IAM 설정 및 수동 배포 방법은
[인프라 문서](../infra/README.md#백엔드-cicd)를 참고하세요.

### 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `JWT_SECRET` | 없음 (필수) | JWT 서명 키. 32바이트 이상이어야 하며, 없으면 기동하지 않음 |
| `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` | 없음 (필수) | 카카오 REST API 키와 클라이언트 시크릿 |
| `AUTH_SUCCESS_REDIRECT` | `http://localhost:3000/oauth/success` | 로그인 성공 후 보낼 프론트 주소 (`docker` 프로필은 배포 주소) |
| `AUTH_FAILURE_REDIRECT` | `http://localhost:3000/login` | 로그인 실패 후 보낼 프론트 주소. `?error=login_failed`가 붙음 |
| `AUTH_COOKIE_SECURE` | `false` | `access_token` 쿠키의 `Secure` 속성. HTTPS 적용 후 `true` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | 허용 오리진(쉼표 구분). Compose는 미지정 시 `PUBLIC_ORIGIN`을 전달하고, `docker` 프로필에서는 필수 |
| `PUBLIC_ORIGIN` | `http://localhost` (Compose) | Caddy 사이트 주소 및 CORS·로그인 완료 주소의 기준. 운영은 `https://iitda.duckdns.org` |
| `RAW_STORAGE_DIR` | `./data/raw-storage` | 기본 프로필의 원본 파일 저장 경로 |
| `RAW_STORAGE_S3_BUCKET` | 없음 (`docker` 필수) | `docker` 프로필의 원본 파일 버킷 |
| `AWS_REGION` | `ap-northeast-2` | S3 리전 |
| `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` | 없음 (`docker` 필수) | `docker` 프로필의 PostgreSQL 연결 정보 |

## 설정 프로필

| 프로필 | 파일 | 용도 | Git 추적 |
| --- | --- | --- | --- |
| 기본 | `application.yml` | 애플리케이션 이름·포트 등 공통 설정 | 예 |
| `docker` | `application-docker.yml` | Docker Compose PostgreSQL 연결 | 예 |
| `test` | `src/test/resources/application-test.yml` | H2 기반 테스트 | 예 |
| `local` | `application-local.yml` | 개발자별 로컬 DB 설정 | 아니오 |
| (항상 로드) | `application-secret.yml` | JWT 서명 키·카카오 앱 키 (`application.yml`이 선택적으로 import) | 아니오 |

## 관련 문서

| 목적 | 문서 |
| --- | --- |
| API 경로, 응답 형식, 오류 코드, HTTP 상태 계약 | [API 규약](../docs/api/api-conventions.md) |
| 패키지 구조, 계층 책임, 테스트와 문서 동기화 규칙 | [AGENTS.md](AGENTS.md) |
| Docker·Caddy 등 인프라 실행 환경 | [인프라 문서](../infra/README.md) |

새 API 구현과 계약 변경은 API 규약을 따른다. 인증·인가를 포함한 API 계약의 예정된
방향도 API 규약에서 관리한다.
