# Backend 구현 가이드

## 목적과 적용 범위

이 문서는 `backend/` 하위의 코드 작성, 수정, 리뷰에 적용하는 구현 기준이다.
에이전트와 개발자는 작업을 시작하기 전에 다음 문서를 확인한다.

- 프로젝트 개요: [`../README.md`](../README.md)
- 백엔드 실행 및 설정: [`README.md`](README.md)
- API 응답 및 오류 규약: [`../docs/api/api-conventions.md`](../docs/api/api-conventions.md)

API 계약은 API 규약을, 의존성과 버전은 `build.gradle`을 기준으로 판단한다.
확인되지 않은 클래스, 패키지, 명령어 또는 문서를 존재하는 것처럼 가정하지 않는다.

## 기술 스택

| 항목 | 내용 |
| --- | --- |
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.5.3 |
| 빌드 | Gradle Wrapper |
| 웹 | Spring Web |
| 요청 검증 | Bean Validation |
| 데이터 접근 | Spring Data JPA |
| 운영 데이터베이스 | PostgreSQL |
| 테스트 데이터베이스 | H2 PostgreSQL 호환 모드 |
| 테스트 | Spring Boot Test, JUnit 5 |
| 보조 도구 | Lombok |

## 작업 흐름

1. README와 API 규약을 읽고 변경 범위를 확인한다.
2. 같은 도메인의 기존 코드와 테스트를 먼저 확인한다.
3. 아래의 패키지 구조와 계층별 책임에 맞게 구현한다.
4. 변경 범위에 맞는 테스트를 추가하고 `./gradlew test`, `./gradlew build`를 실행한다.
5. 외부 계약이나 개발 절차가 달라졌다면 관련 문서를 같은 변경에서 갱신한다.

## 패키지 구조 원칙

패키지 루트는 `com.itda.backend`다. 계층 전체를 먼저 나누지 않고 기능을 기준으로
도메인을 나눈 뒤, 각 도메인 내부에서 계층을 구분한다.

```text
com.itda.backend
├── domain
│   └── {domain}
│       ├── controller
│       ├── service
│       ├── repository
│       ├── entity
│       ├── dto
│       │   ├── request
│       │   └── response
│       └── exception
└── global
    ├── response
    └── exception
```

- 특정 도메인에서만 사용하는 코드는 해당 `domain/{domain}` 아래에 둔다.
- 여러 도메인에서 공통으로 사용하는 코드만 `global`에 둔다.
- 한 도메인에서 다른 도메인의 Repository나 Entity를 직접 사용하지 않는다.
- 도메인 간 협력이 필요하면 상대 도메인의 Service를 통해 요청한다.
- 새로운 공통 패키지는 실제로 둘 이상의 도메인에서 공유할 때만 추가한다.

## 코드 컨벤션

### Controller

- 요청 매핑, Request DTO의 `@Valid` 검증, Service 호출, 반환 DTO의 `ApiResponse` 포장만 담당한다.
- 비즈니스 규칙, 데이터베이스 조회, Entity 상태 변경 로직을 작성하지 않는다.
- 외부 API 경로는 `/api/v1`을 기준으로 한다.
- JPA Entity를 API 요청이나 응답에 직접 노출하지 않는다.
- 일반 JSON 성공 응답은 `global.response.ApiResponse<T>`로 감싼다.
- 상태 코드나 응답 헤더를 직접 지정해야 할 때는 `ResponseEntity<ApiResponse<T>>`를 사용할 수 있다.
- 파일 다운로드, 스트리밍, `204 No Content`에는 `ApiResponse`를 사용하지 않는다.

### DTO

- Request DTO와 Response DTO를 각각 `dto/request`, `dto/response`에 둔다.
- DTO 이름은 용도를 드러내도록 `{행위}{도메인}Request`, `{도메인}Response` 형식을 사용한다.
- 서로 다른 유스케이스의 DTO는 필드가 같더라도 목적이 다르면 분리한다.
- DTO는 불변 객체를 우선하며, 적합한 경우 Java `record`를 사용한다.
- Request DTO는 입력 데이터와 형식 검증만 담당하며 Entity를 생성하거나 변경하지 않는다.
- Response DTO는 응답 데이터만 표현하고 Repository나 Service에 의존하지 않는다.
- Entity에서 Response DTO로의 변환은 Service 또는 명시적인 Mapper에서 일관되게 처리한다.

### Entity

- Entity는 데이터베이스 매핑과 생성·상태 변경에 필요한 도메인 규칙을 표현한다.
- API 응답 모양을 맞추기 위한 필드를 Entity에 추가하지 않는다.
- public setter와 Lombok `@Data`를 사용하지 않는다.
- JPA 기본 생성자는 `protected`로 제한한다.
- 생성자 또는 정적 팩토리 메서드로 유효한 상태의 Entity를 생성한다.
- 상태 변경은 `update`, `approve`, `withdraw`처럼 의도가 드러나는 메서드로 수행한다.
- 연관관계는 지연 로딩을 우선하고, 양방향 연관관계는 필요한 경우에만 사용한다.

### Service

- 비즈니스 규칙과 유스케이스 흐름, 트랜잭션 경계를 담당한다.
- Controller, `HttpServletRequest`, `ResponseEntity` 같은 웹 계층 타입에 의존하지 않는다.
- 조회 작업은 `@Transactional(readOnly = true)`, 변경 작업은 `@Transactional`로 범위를 명시한다.
- 중복, 권한, 존재 여부 등 저장소 조회가 필요한 비즈니스 규칙을 검증한다.
- 조회 결과가 없거나 비즈니스 규칙을 위반하면 `null`을 반환하지 않고 도메인 예외를 발생시킨다.
- Request DTO에서 Entity를 생성하거나 복잡한 객체를 조립하는 책임은 Service 또는 명시적인 Mapper에 둔다.

### Repository

- Entity 조회와 저장에 집중한다.
- 비즈니스 규칙을 판단하거나 Response DTO를 조립하지 않는다.
- 복잡한 조회가 필요하면 지나치게 긴 쿼리 메서드 이름 대신 명시적인 쿼리 또는 별도 Repository 구현을 사용한다.

## 검증과 변환 책임

| 구분 | 책임 위치 | 예시 |
| --- | --- | --- |
| 요청 형식 검증 | Request DTO | 필수값, 문자열 길이, 이메일 형식 |
| Entity 유효성 | Entity | 생성 가능 상태, 상태 변경 조건, 값의 불변 조건 |
| 비즈니스 규칙 | Service | 중복 여부, 접근 권한, 대상 존재 여부 |
| Request → Entity | Service 또는 명시적인 Mapper | Entity 생성, 연관 객체 조립 |
| Entity → Response | Service 또는 명시적인 Mapper | 조회 결과를 Response DTO로 변환 |

Bean Validation을 통과했더라도 Entity는 자신의 불변 조건을 스스로 보호해야 한다.
Mapper를 사용하는 경우에도 데이터 변환만 담당하게 하고 조회나 비즈니스 판단을 넣지 않는다.

## API 응답과 예외 처리

### ApiResponse

- 일반 JSON 성공 응답은 `global.response.ApiResponse<T>`를 사용한다.
- 성공 응답은 `ApiResponse.success(data)`처럼 의도가 분명한 팩토리 메서드로 생성한다.
- 문자열 데이터와 메시지가 혼동되는 `success(String)` 같은 오버로드는 만들지 않는다.
- 메시지 전용 생성이 필요하면 `successWithMessage`처럼 역할이 드러나는 이름을 사용한다.
- 필드 구성과 `SUCCESS`, `FAIL` 값은 [API 규약](../docs/api/api-conventions.md)을 따른다.

### ErrorCode와 예외 처리

- `ErrorCode`는 HTTP 상태, 안정적인 오류 코드, 사용자에게 노출 가능한 메시지와 오류 타입 정보를 제공한다.
- 공통 오류는 `CommonErrorCode`, 도메인 오류는 `{Domain}ErrorCode` 형태의 enum으로 분리한다.
- 오류 코드는 `UPPER_SNAKE_CASE`를 사용한다.
- 도메인 예외는 해당 `ErrorCode`를 보관하고, 비즈니스 실패를 표현한다.
- `@RestControllerAdvice` 전역 예외 처리기는 예외를 API 규약의 실패 응답과 적절한 HTTP 상태 코드로 변환한다.
- 실패 응답을 `200 OK`로 반환하지 않는다.
- 내부 예외 메시지, SQL, 비밀번호, 토큰, 스택 트레이스를 API 응답에 노출하지 않는다.

API 계약을 변경하면 `docs/api/api-conventions.md`를 함께 갱신한다. 실제
OpenAPI/Swagger 명세가 도입된 이후에는 해당 명세도 같은 변경에서 갱신한다.

## 테스트 원칙

`backend/`에서 최소한 다음 명령을 실행한다.

```bash
./gradlew test
./gradlew build
```

- 변경한 유스케이스의 성공 경로와 주요 실패 경로를 함께 테스트한다.
- DB 매핑, JPA 쿼리, 트랜잭션 관련 변경에는 H2 PostgreSQL 호환 모드 또는 Testcontainers PostgreSQL로 실행되는 테스트를 추가한다.
- DB 테스트는 `test` 프로필을 사용하고 기존 `application-test.yml` 설정을 유지한다.
- 단위 테스트와 빠른 스모크 테스트는 Docker 없이 실행 가능하게 유지한다.
- PostgreSQL 고유 동작, JPA 매핑·쿼리, 트랜잭션 검증이 필요한 통합 테스트에만 Testcontainers를 사용한다.
- Testcontainers를 사용하는 테스트에는 `@Tag("integration")`을 붙여 통합 테스트임을 명확히 구분한다.
- 인증·인가 코드를 수정한 경우 성공, 인증 실패, 권한 거부 경로를 검증한다.
- 문서만 변경한 경우에도 링크, 경로, 코드 예시와 실제 설정의 일치 여부를 확인한다.

## Git 규칙

- 커밋 메시지는 변경 의도가 드러나도록 한글로 작성한다.

## 문서 동기화

| 변경 유형 | 함께 확인하거나 수정할 문서 |
| --- | --- |
| API 경로, 요청·응답, HTTP 상태, 오류 코드 | `docs/api/api-conventions.md`, 실제 OpenAPI/Swagger 명세가 있다면 해당 명세 |
| JDK, Spring Boot, 의존성, DB 변경 | `build.gradle`, `backend/README.md`, 이 문서의 기술 스택 |
| 실행 명령, 프로필, 환경 변수 변경 | `backend/README.md` |
| 패키지 구조나 계층 책임 변경 | `backend/AGENTS.md` |
| 인증·인가 API 계약 변경 | `docs/api/api-conventions.md`, 실제 OpenAPI/Swagger 명세가 있다면 해당 명세 |
| 새 문서 추가 또는 문서 위치 변경 | `docs/README.md`와 연결되는 문서의 링크 |
| 외부 계약이 변하지 않는 내부 리팩터링 | 문서 영향 여부를 확인하고, 영향이 없으면 수정하지 않음 |

저장소에 존재하지 않는 문서 경로와 API 문서 생성 명령은 추측해 추가하지 않는다.

## 커밋 단위

- 커밋은 파일 종류가 아니라 기능 구현 흐름의 논리적 단계로 나눈다.
- 하나의 기능은 도메인 모델, 서비스, API, 테스트, 문서 순서로 작은 커밋으로 분리할 수 있다.
- DTO, Repository 쿼리, Mapper 등은 가장 가까운 기능 단계의 커밋에 포함한다.
- 각 커밋의 메시지는 변경한 레이어가 아니라 기능 의도를 표현한다.
- 테스트와 API 계약 문서는 별도 커밋으로 둘 수 있지만, 기능 브랜치를 병합하기 전에는 반드시 반영하고 검증한다.
- 다른 도메인·기능·무관한 리팩터링은 같은 커밋에 섞지 않는다.

### 예시: 기관 등록 기능

```text
feat: 기관 엔티티와 저장소 추가
feat: 기관 등록 서비스 추가
feat: 기관 등록 API 추가
test: 기관 등록 기능 테스트 추가
docs: 기관 등록 API 계약 추가
```

### 커밋 메시지 형식

`<type>: <변경 요약>` 형식을 사용한다.

- `feat`: 기능 추가
- `fix`: 버그 수정
- `test`: 테스트 추가·보완
- `docs`: 문서 변경
- `refactor`: 동작 변경 없는 구조 개선
- `chore`: 빌드, 도구, Git 설정 등 기타 작업

이 문서에 정의되지 않은 브랜치·커밋 규칙은 임의로 가정하지 않는다.
