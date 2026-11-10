# API 규약

모든 외부 API가 따르는 약속입니다 — 경로 · 응답 형식 · 오류 · 인증. 엔드포인트별 요청 · 응답은 Swagger에 있습니다.

- Swagger UI: 백엔드를 띄운 뒤 `http://localhost:8080/swagger-ui/index.html` (OpenAPI: `/v3/api-docs` · `/v3/api-docs.yaml`). 운영은 Caddy가 막아 SSH 터널로 봅니다
- 엔드포인트가 도메인에서 어떤 일을 하는지는 [도메인 문서](../README.md#도메인)에 있습니다

---

## 1. 범위

- 외부 API는 `/api/v1` 아래에 둡니다. 요청 · 응답 본문은 JSON, 필드 이름은 **camelCase**입니다
- 응답 래퍼를 쓰지 않는 것:
  - `GET /api/health` — 배포 확인용, `{ "status": "ok" }`를 그대로 줍니다
  - 카카오 로그인 진입(`/oauth2/authorization/kakao`) · 콜백(`/login/oauth2/code/kakao`) — 브라우저 리다이렉트
  - 카카오 연결 해제 웹훅, 개발용 로그인(`/api/v1/dev/login`) 같은 리다이렉트 · 외부 호출 응답
  - `204 No Content`
- 백엔드 ↔ AI 서버 통신은 이 규약 밖입니다. 필드가 snake_case이고 ID를 숫자로 주고받습니다 ([AI/README.md](../../AI/README.md#api))

---

## 2. 응답 형식

**성공** — `result`와 `data`, 안내 문구가 필요할 때만 `message`. 값이 `null`인 필드는 빠집니다.

```json
{ "result": "SUCCESS", "data": { "id": "1", "name": "김하늘" } }
```

**실패** — 알맞은 HTTP 상태와 함께 `result` · `code` · `message`를 모두 줍니다.

```json
{ "result": "FAIL", "code": "CHILD_NOT_FOUND", "message": "아동을 찾을 수 없습니다." }
```

- `code`는 화면이 분기에 쓰는 안정적인 식별자(`UPPER_SNAKE_CASE`)이고, `message`는 사람에게 보여 줄 수 있는 문구입니다
- 내부 예외 · SQL · 토큰 · 아이 기록 같은 상세는 `message`에 넣지 않고 서버 로그에만 남깁니다
- **실패를 `200`으로 주지 않습니다**

---

## 3. HTTP 상태와 오류 코드

| 상태 | 언제 | 예 |
| --- | --- | --- |
| `200` · `201` · `202` · `204` | 성공 · 생성 · 접수만 함 · 본문 없음 | 탈퇴는 바로 끝나면 `204`, 오래 걸리면 `202` |
| `400` | 입력 형식 · 검증 오류 | `INVALID_REQUEST` · `INVALID_INVITE_CODE` |
| `401` | 로그인이 없거나 더 이상 누구도 가리키지 않음 | `UNAUTHORIZED` · `SESSION_USER_NOT_FOUND` · `ACCOUNT_WITHDRAWN` |
| `403` | 로그인했지만 할 수 없음 | `FORBIDDEN` · `SIGNUP_NOT_COMPLETED` · `TERMS_NOT_AGREED` · `ORGANIZATION_NOT_ASSIGNED` · `PARENT_REQUIRED` |
| `404` | 없음, 또는 **볼 권한이 없음** (있는지 알려 주지 않음) | `NOT_FOUND` · `CHILD_NOT_FOUND` · `SHARING_NOT_FOUND` |
| `405` · `415` | 지원하지 않는 메서드 · 요청 형식 | `METHOD_NOT_ALLOWED` · `UNSUPPORTED_MEDIA_TYPE` |
| `409` | 지금 상태와 충돌 · 중복 | `ALREADY_SIGNED_UP` · `SUMMARY_CHANGED` · `WITHDRAWAL_IN_PROGRESS` |
| `413` | 업로드 크기 초과 (20MB) | `RAW_RECORD_FILE_TOO_LARGE` |
| `429` | 지금 너무 많음 — 잠시 뒤 다시 | `RAW_RECORD_UPLOAD_BUSY` · `AGENT_QUEUE_FULL` |
| `500` | 예상하지 못한 서버 오류 | `INTERNAL_SERVER_ERROR` |
| `502` | 외부 서비스(카카오)가 실패를 돌려줌 | `KAKAO_UNLINK_FAILED` |
| `503` | 잠깐의 실패 — 다시 보내면 됨 | `INVITE_CODE_ISSUE_FAILED` (코드가 동시에 겹침) |

**오류 코드 규칙**

- 공통 오류는 `CommonErrorCode` — `INVALID_REQUEST` · `UNAUTHORIZED` · `FORBIDDEN` · `NOT_FOUND` · `METHOD_NOT_ALLOWED` · `UNSUPPORTED_MEDIA_TYPE` · `INTERNAL_SERVER_ERROR`
- 도메인 오류는 도메인마다 `{도메인}ErrorCode` enum으로 둡니다 (`ChildErrorCode` 등). 엔드포인트마다 어떤 코드가 나는지는 Swagger에 적습니다
- 경로 변수 타입 불일치, 필수 파라미터 누락, 읽을 수 없는 본문처럼 Spring이 요청 단계에서 거부하는 오류도 `400 INVALID_REQUEST`와 같은 실패 형식으로 줍니다
- 이미 나간 코드의 이름과 상태는 함부로 바꾸지 않습니다. 화면이 그 값으로 분기하기 때문입니다

---

## 4. 값 규칙

- **ID는 문자열**로 내립니다 (`"1"`). JavaScript가 큰 정수를 정확히 다루지 못하기 때문입니다. 접두사는 붙이지 않고, 요청의 ID는 문자열과 숫자를 모두 받습니다
  - ⚠️ **지금 숫자로 나가는 곳**: 원본 기록 · 업로드(`id` · `uploadId`), 확인 필요 큐와 수정 요청 큐의 `journalEntryId`, 1차 검토 목록과 결정 응답(`id` · `childId` · `summaryId` · 근거의 `journalEntryId`), 요약 실패 목록. 새 API는 문자열로 씁니다
- **날짜**는 `YYYY-MM-DD`, **시각**은 ISO 8601입니다
  - ⚠️ 대부분 오프셋 없는 서울 시각(`2026-11-10T14:30:00`)이고, 일부는 `+09:00`이 붙습니다. 오프셋이 없어도 서울 시각입니다
- 값이 없다는 뜻이 필요하면 `null` 대신 상태 값이나 빈 배열을 씁니다

---

## 5. 인증 · 인가

로그인 · 가입 · 탈퇴의 흐름은 [계정 도메인](../domains/account.md)에 있습니다. 여기서는 모든 API에 걸리는 규칙만 적습니다.

**출입증** — 로그인하면 `access_token` httpOnly 쿠키가 생깁니다.

- 화면은 쿠키를 읽거나 `Authorization` 헤더에 넣지 않고, `credentials: include`로 브라우저가 보내게 합니다
- JWT의 subject는 내부 회원 ID입니다. 역할과 소속 기관은 토큰에 담지 않고 요청마다 DB에서 읽어, 바뀌면 바로 반영됩니다
- JWT는 3시간이고, 남은 시간이 절반보다 적으면 `access_token_renewal_{sid}` 쿠키로 늘려 줍니다. 처음 로그인부터 12시간에 끝납니다. refresh token은 없습니다
- 세션에 로그인 정보를 두지 않습니다. 세션은 카카오 인가 요청의 state를 잠깐 보관할 때만 씁니다

**CSRF** — `POST` · `PUT` · `PATCH` · `DELETE`는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더에 담아야 합니다. 없으면 `403`입니다. 카카오 로그인 경로와 카카오 웹훅은 빠집니다(웹훅은 `Authorization: KakaoAK {어드민 키}`로 검증).

**모든 보호 API에 걸리는 확인** — 보안 필터 한 곳에서 이 순서로 봅니다. 새 API에도 자동으로 걸립니다.

| 상황 | 응답 | 예외로 허용하는 API |
| --- | --- | --- |
| 로그인 없음 · 만료 · 로그아웃 | `401 UNAUTHORIZED` | |
| 출입증이 가리키는 회원이 없음 | `401 SESSION_USER_NOT_FOUND` | |
| 탈퇴를 마친 회원 | `401 ACCOUNT_WITHDRAWN` | |
| 탈퇴 처리 중 | `409 WITHDRAWAL_IN_PROGRESS` | `/auth/me` |
| 역할을 고르지 않음 (가입 미완료) | `403 SIGNUP_NOT_COMPLETED` | `/auth/me` · `/auth/signup` · `/auth/logout` · `/dev/login` |
| 약관에 동의하지 않음 | `403 TERMS_NOT_AGREED` | `/auth/me` · `/auth/terms` · `/auth/logout` |

- 그다음 각 API가 역할을 봅니다 — 기관 API에 기관 소속이 아니면 `403 ORGANIZATION_NOT_ASSIGNED`, 보호자 API에 보호자가 아니면 `403 PARENT_REQUIRED`
- 화면은 `401`을 "로그인 화면으로"로 읽습니다. 탈퇴 안내 화면만 `ACCOUNT_WITHDRAWN`을 "탈퇴가 끝났다"로 보여 줍니다
- 로그인 실패(동의 취소 · 카카오 장애 등)는 JSON이 아니라 실패 주소에 `?error=login_failed`를 붙인 리다이렉트로 알립니다

**비밀값** — 카카오 시크릿과 `jwt.secret`은 환경 변수나 Git이 무시하는 `application-secret.yml`로만 줍니다 ([예시 파일](../../backend/src/main/resources/application-secret.example.yml)).

---

## 6. API를 바꿀 때

1. 컨트롤러의 OpenAPI 설명(`@Operation` · 응답 코드)을 같은 변경에서 고칩니다. Swagger가 엔드포인트 명세의 원본입니다
2. 모든 엔드포인트에 공통으로 붙는 것(쿠키 인증 표시, `401`, 오류 본문 형식)은 `OpenApiConfig`가 붙이니 컨트롤러에는 엔드포인트마다 다른 응답만 적습니다
3. 성공 · 입력 오류 · 인증 · 권한 · 도메인 오류 경로를 테스트합니다
4. 이 문서의 규약이 바뀌면 이 문서를, 도메인 동작이 바뀌면 해당 [도메인 문서](../README.md#도메인)를 함께 고칩니다

자세한 구현 규칙은 [backend/AGENTS.md](../../backend/AGENTS.md)에 있습니다.
