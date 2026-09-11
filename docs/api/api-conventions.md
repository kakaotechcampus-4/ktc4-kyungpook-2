# API 규약

> 상태: **구현 예정**
>
> 이 문서는 백엔드 구현 전에 합의한 외부 API 계약이다. 현재 공통 응답 래퍼,
> 오류 코드 enum, 전역 예외 처리, Spring Security는 구현되지 않았다. 첫 API 구현 시
> 이 규약을 코드에 반영한다. 실제 OpenAPI/Swagger 명세를 도입한 이후에는 해당 명세도 함께 갱신한다.

관련 문서:

- 현재 백엔드 구현 상태와 실행 방법: [backend/README.md](../../backend/README.md)
- 백엔드 구현·테스트 규칙: [backend/AGENTS.md](../../backend/AGENTS.md)

## 적용 범위

- 새 외부 API의 기본 경로는 `/api/v1`이다.
- 요청·응답 본문은 JSON을 기본으로 한다.
- 파일 다운로드·스트리밍·웹훅처럼 원본 형식이 필요한 응답은 이 문서의 응답 래퍼를 적용하지 않을 수 있다.
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
  "message": "사용자를 찾을 수 없습니다.",
  "type": "/problems/user/not-found"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `result` | string | 예 | 실패 시 `FAIL` |
| `code` | string | 예 | 프로그램이 처리하는 오류 코드 (`UPPER_SNAKE_CASE`) |
| `message` | string | 예 | 사용자에게 노출 가능한 메시지 |
| `type` | string | 아니오 | 오류 유형 식별 URI. Problem Details 형식과의 호환을 위한 확장 필드 |

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
| 중복·현재 상태와 충돌 | `409 Conflict` | `DUPLICATE_EMAIL` |
| 업로드 용량 초과 | `413 Payload Too Large` | `FILE_TOO_LARGE` |
| 예상하지 못한 서버 오류 | `500 Internal Server Error` | `INTERNAL_SERVER_ERROR` |

`204 No Content` 응답에는 JSON 래퍼를 포함하지 않는다.

## 오류 코드 규칙

- 코드명은 `UPPER_SNAKE_CASE`를 사용한다.
- 공통 오류는 `CommonErrorCode` 같은 공통 enum으로 관리한다.
- 도메인 오류는 `UserErrorCode`, `OrganizationErrorCode`처럼 도메인별 enum으로 분리한다.
- 공통 오류의 초기 목록은 `INVALID_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL_SERVER_ERROR`다.
- 오류 코드는 이름과 HTTP 상태를 함부로 변경하지 않는다. 변경이 필요하면 클라이언트 영향도를 확인한다.

## 인증·인가 규약

> 상태: **구현 예정**. 카카오 로그인 기능을 시작하는 변경에서 Spring Security와 함께 구현한다.

- 카카오 로그인은 외부 신원 확인 수단이며, 서비스 회원·역할·권한의 기준은 내부 DB다.
- 카카오 사용자 ID를 내부 회원에 연결한 뒤 `PARENT`, `ORGANIZATION`, `ADMIN` 역할로 인가한다.
- 보호 API는 인증되지 않은 요청에 `401`, 역할이 맞지 않는 요청에 `403`을 반환한다.
- 카카오 OAuth 2.0 클라이언트 시크릿은 환경 변수 또는 무시되는 `application-local.yml`로만 제공한다.
- 세션 기반 인증을 우선 검토한다. JWT 등 다른 방식을 채택하면 토큰 보관·만료·폐기·CSRF 대응 정책을 ADR에 기록한다.

## 변경 절차

1. API를 추가하거나 계약을 변경하기 전에 이 문서를 갱신한다. 실제 OpenAPI/Swagger 명세가 도입된 이후에는 해당 명세도 함께 갱신한다.
2. 컨트롤러, DTO, 예외 처리 구현을 반영한다.
3. 성공·입력 오류·인증/인가 오류·도메인 오류 경로를 테스트한다.
4. 프론트엔드에 영향을 주는 변경은 버전 또는 마이그레이션 계획을 함께 공유한다.
