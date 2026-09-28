# API 규약

> 상태: **공통 응답 래퍼·오류 코드·전역 예외 처리·Spring Security 구현 완료**
>
> 이 문서는 백엔드 구현 전에 합의한 외부 API 계약이다. 공통 응답 래퍼(`ApiResponse`/`ErrorResponse`),
> 오류 코드 enum(`CommonErrorCode`/`AuthErrorCode`), 전역 예외 처리(`GlobalExceptionHandler`),
> Spring Security(JWT 인증 + CORS + 401/403 JSON 응답)까지 카카오 로그인 API에 반영돼 있다.
> 회원 DB 연결과 역할(role) 기반 인가는 아직 구현 예정이다. Swagger UI는
> `/swagger-ui/index.html`, OpenAPI 명세는 `/v3/api-docs`와 `/v3/api-docs.yaml`에서
> 확인할 수 있으며, 외부 API 변경 시 해당 명세를 같은 변경에서 갱신한다.

관련 문서:

- 현재 백엔드 구현 상태와 실행 방법: [backend/README.md](../../backend/README.md)
- 백엔드 구현·테스트 규칙: [backend/AGENTS.md](../../backend/AGENTS.md)

## 적용 범위

- 새 외부 API의 기본 경로는 `/api/v1`이다.
- 요청·응답 본문은 JSON을 기본으로 한다.
- 파일 다운로드·스트리밍·웹훅처럼 원본 형식이 필요한 응답은 이 문서의 응답 래퍼를 적용하지 않을 수 있다.
- 상태 확인용 `GET /api/health`는 인프라 점검용이라 `/api/v1` 경로와 응답 래퍼를 적용하지 않고
  `{ "status": "ok" }`를 그대로 반환한다.
- 카카오 로그인 진입(`/oauth2/authorization/kakao`)과 콜백(`/login/oauth2/code/kakao`)은 Spring
  Security가 제공하는 브라우저 리다이렉트 경로라 `/api/v1` 밖에 있고 JSON을 반환하지 않는다.
- `null` 값은 가능한 한 응답에서 생략한다. 값이 없다는 의미가 필요하면 명시적인 상태나 빈 배열을 사용한다.

## 성공 응답

성공 응답은 `result`와 `data`를 기본으로 하고, 안내 문구가 필요할 때만 `message`를 넣는다.

```json
{
  "result": "SUCCESS",
  "data": {
    "id": 1,
    "name": "예시 사용자"
  },
  "message": "조회했습니다."
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | string | 예 | 성공 시 `SUCCESS` |
| `data` | object, array, scalar | 아니오 | 실제 응답 데이터 |
| `message` | string | 아니오 | 사용자 또는 클라이언트용 보조 메시지 |

데이터가 없는 정상 처리에는 `data`를 생략할 수 있다.

```json
{
  "result": "SUCCESS",
  "message": "삭제했습니다."
}
```

## 실패 응답

실패 응답은 올바른 HTTP 상태 코드와 함께 반환한다. 본문의 `code`는 클라이언트가
세부 동작을 분기할 수 있는 안정적인 식별자이며, `message`는 사람이 읽는 안내 문구다.

```http
HTTP/1.1 404 Not Found
```

```json
{
  "result": "FAIL",
  "code": "USER_NOT_FOUND",
  "message": "사용자를 찾을 수 없습니다."
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | string | 예 | 실패 시 `FAIL` |
| `code` | string | 예 | 프로그램이 처리하는 오류 코드 (`UPPER_SNAKE_CASE`) |
| `message` | string | 예 | 사용자에게 노출 가능한 메시지 |

서버 내부 예외, SQL, 액세스 토큰, 비밀번호 등 민감한 상세 내용은 응답 `message`에
넣지 않는다. 상세 원인은 서버 로그에만 기록한다.

## HTTP 상태 코드 원칙

실패를 `200 OK`로 반환하지 않는다. HTTP 상태 코드는 전송·프로토콜 수준의 결과를,
본문의 `code`는 서비스 수준의 세부 원인을 표현한다.

| 상황 | 상태 코드 | 예시 코드 |
| --- | --- | --- |
| 조회·수정 성공 | `200 OK` | - |
| 새 리소스 생성 성공 | `201 Created` | - |
| 본문 없는 삭제 성공 | `204 No Content` | - |
| 입력 형식·검증 오류 | `400 Bad Request` | `INVALID_REQUEST` |
| 인증되지 않은 요청 | `401 Unauthorized` | `UNAUTHORIZED` |
| 인증됐지만 권한 없음 | `403 Forbidden` | `FORBIDDEN` |
| 대상 리소스 없음 | `404 Not Found` | `USER_NOT_FOUND` |
| 존재하지 않는 경로 | `404 Not Found` | `NOT_FOUND` |
| 지원하지 않는 HTTP 메서드 | `405 Method Not Allowed` | `METHOD_NOT_ALLOWED` |
| 중복·현재 상태와 충돌 | `409 Conflict` | `DUPLICATE_EMAIL` |
| 업로드 용량 초과 | `413 Payload Too Large` | `FILE_TOO_LARGE` |
| 지원하지 않는 요청 형식(Content-Type) | `415 Unsupported Media Type` | `UNSUPPORTED_MEDIA_TYPE` |
| 예상하지 못한 서버 오류 | `500 Internal Server Error` | `INTERNAL_SERVER_ERROR` |
| 외부 서비스(카카오 등) 서버 오류 | `502 Bad Gateway` | `KAKAO_SERVER_ERROR` |
| 외부 서비스 연결 실패·타임아웃 | `503 Service Unavailable` | `KAKAO_UNAVAILABLE` |

`204 No Content` 응답에는 JSON 래퍼를 포함하지 않는다.

## 오류 코드 규칙

- 코드명은 `UPPER_SNAKE_CASE`를 사용한다.
- 공통 오류는 `CommonErrorCode` 같은 공통 enum으로 관리한다.
- 도메인 오류는 `UserErrorCode`, `OrganizationErrorCode`처럼 도메인별 enum으로 분리한다.
- 공통 오류 목록은 `INVALID_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`,
  `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_SERVER_ERROR`다.
- 경로 변수 타입 불일치, 필수 파라미터·멀티파트 파트 누락, 읽을 수 없는 요청 본문처럼 Spring MVC가
  요청 단계에서 거부하는 오류는 `INVALID_REQUEST`(`400`)로 응답한다. 이때도 실패 응답 본문 형식은 같다.
- 오류 코드는 이름과 HTTP 상태를 함부로 변경하지 않는다. 변경이 필요하면 클라이언트 영향도를 확인한다.

## 인증·인가 규약

> 상태: **카카오 인증 + JWT 발급 구현 완료 · 회원 DB 연결과 역할(role) 기반 인가는 예정**

- 카카오 로그인은 외부 신원 확인 수단이며, 서비스 회원·역할·권한의 기준은 내부 DB다. (DB 연결은 예정)
- 카카오 사용자 ID를 내부 회원에 연결한 뒤 `PARENT`, `ORGANIZATION`, `ADMIN` 역할로 인가한다. (예정)
- 보호 API는 인증되지 않은 요청에 `401`, 역할이 맞지 않는 요청에 `403`을 반환한다. 401은 구현·검증 완료. 403은 핸들러(`JsonAccessDeniedHandler`)까지는 구현돼 있으나, 아직 역할 기반으로 막힌 API가 없어 실제로 도달하는 경로는 없다.
- 카카오 OAuth 2.0 클라이언트 시크릿과 `jwt.secret`은 환경 변수 또는 무시되는 `application-secret.yml`로만 제공한다 ([예시 파일](../../backend/src/main/resources/application-secret.yml.example) 참고).
- 인증 방식은 JWT 쿠키다. 브라우저는 `GET /oauth2/authorization/kakao`로 이동해 카카오 로그인을 시작한다. 카카오 콜백(`GET /login/oauth2/code/kakao`)은 Spring Security가 처리하며, 클라이언트가 직접 호출하지 않는다. 성공 시 서버는 `access_token` httpOnly 쿠키를 발급하고 설정된 프론트엔드 주소로 리다이렉트한다.
- 로그인 실패(state 불일치, 동의 취소, 카카오 장애 등)는 JSON 오류가 아니라 설정된 실패 주소에
  `?error=login_failed`를 붙인 리다이렉트로 전달한다. 상세 원인은 서버 로그에만 남긴다. 그래서 위
  표의 `KAKAO_SERVER_ERROR`·`KAKAO_UNAVAILABLE`과 `AuthErrorCode`의 `KAKAO_AUTH_FAILED`는 현재
  로그인 흐름에서 응답으로 나가지 않는다.
- 보호 API 요청은 브라우저가 `access_token` 쿠키를 자동으로 전송하도록 `credentials: include`를 사용한다. 클라이언트는 JWT를 읽거나 `Authorization: Bearer` 헤더에 직접 넣지 않는다.
- `POST`, `PUT`, `PATCH`, `DELETE` 요청에는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더에 함께 보낸다. OAuth 로그인 시작·콜백 경로는 이 CSRF 검사에서 제외된다.
- OAuth 인가 요청의 state 보관에만 짧게 HTTP 세션을 사용하고, 성공·실패 처리 후 세션을 폐기한다. 이후 API 인증은 JWT 쿠키로 처리한다. refresh token 발급과 토큰 폐기 전략은 아직 없다. 로그아웃은 `POST /api/v1/auth/logout`이 `access_token` 쿠키를 만료시킨다.

## 변경 절차

1. API를 추가하거나 계약을 변경하기 전에 이 문서와 OpenAPI/Swagger 명세를 함께 갱신한다.
2. 컨트롤러, DTO, 예외 처리 구현을 반영한다.
3. 성공·입력 오류·인증/인가 오류·도메인 오류 경로를 테스트한다.
4. 프론트엔드에 영향을 주는 변경은 버전 또는 마이그레이션 계획을 함께 공유한다.
