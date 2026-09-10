# Backend

잇다(ITDA)의 API 서버입니다. 기관과 학부모가 이용하는 서비스의 도메인 로직,
데이터베이스 연동, 인증·인가를 담당합니다.

## 현재 상태

기본 Spring Boot 애플리케이션과 데이터베이스 연결 설정만 준비되어 있습니다.
도메인 API, 공통 응답 래퍼, 전역 예외 처리, 카카오 로그인과 Spring Security 설정은
**구현 예정**입니다. 이 문서는 구현 전에 팀이 합의한 기준을 기록합니다.

## 기술 스택

| 항목 | 사용 기술 |
| --- | --- |
| 언어·런타임 | Java 21 |
| 프레임워크 | Spring Boot 3.5.3 |
| 웹·검증 | Spring Web, Bean Validation |
| 데이터 접근 | Spring Data JPA |
| 운영 DB | PostgreSQL |
| 테스트 DB | H2 (PostgreSQL 호환 모드) |
| 빌드 | Gradle Wrapper |

## 시작하기

### 사전 조건

- JDK 21
- Docker Desktop (Docker 환경으로 실행할 경우)
- 로컬 실행 시 PostgreSQL

### 테스트와 빌드

`backend/`에서 실행합니다.

```bash
./gradlew test
./gradlew build
```

Windows에서는 `gradlew.bat test`와 `gradlew.bat build`를 사용합니다.

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

비밀번호, 카카오 클라이언트 시크릿, 운영 키는 어떤 `application*.yml`에도 커밋하지
않습니다. 환경 변수 또는 무시되는 로컬 프로필 파일로만 제공합니다.

### Docker Compose로 실행

루트의 `infra/docker/.env.example`을 참고해 `infra/docker/.env`를 만든 후 실행합니다.

```bash
cd ../infra/docker
docker compose up --build
```

Compose 환경에서는 `docker` 프로필이 활성화되고, `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD` 환경 변수로 PostgreSQL에 연결합니다. 백엔드는 호스트의
`127.0.0.1:8080`에 바인딩됩니다.

## 설정 프로필

| 프로필 | 파일 | 용도 | Git 추적 |
| --- | --- | --- | --- |
| 기본 | `application.yml` | 애플리케이션 이름·포트 등 공통 설정 | 예 |
| `docker` | `application-docker.yml` | Docker Compose PostgreSQL 연결 | 예 |
| `test` | `src/test/resources/application-test.yml` | H2 기반 테스트 | 예 |
| `local` | `application-local.yml` | 개발자별 로컬 DB·개인 키 | 아니오 |

## 관련 문서

| 목적 | 문서 |
| --- | --- |
| API 경로, 응답 형식, 오류 코드, HTTP 상태 계약 | [API 규약](../docs/api/api-conventions.md) |
| 패키지 구조, 계층 책임, 테스트와 문서 동기화 규칙 | [AGENTS.md](AGENTS.md) |
| Docker·Nginx 등 인프라 실행 환경 | [인프라 문서](../infra/README.md) |

새 API 구현과 계약 변경은 API 규약을 따른다. 인증·인가를 포함한 API 계약의 예정된
방향도 API 규약에서 관리한다.
