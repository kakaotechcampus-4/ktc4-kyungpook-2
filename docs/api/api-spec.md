# API 명세 (프론트엔드 → 백엔드 요청)

> 작성: 프론트엔드
> 상태: **협의용 초안**. 프론트 화면 구현이 끝난 시점에서 필요한 엔드포인트를 정리한 것입니다.
> 경로 · 필드명은 백엔드 의견에 따라 조정 가능하며, 조정되면 이 문서와
> [feature-interfaces.md](../frontend/feature-interfaces.md)를 함께 갱신합니다.

이 문서는 [api-conventions.md](api-conventions.md)의 규약(공통 응답 래퍼, 오류 코드,
HTTP 상태)을 **전제**로 합니다. 아래 예시의 `data` 안 내용만 도메인별로 정의합니다.

| 문서 | 역할 |
| --- | --- |
| [api-conventions.md](api-conventions.md) | 응답 래퍼 · 오류 코드 · HTTP 상태 규약 |
| [../frontend/feature-interfaces.md](../frontend/feature-interfaces.md) | 프론트 타입 · API 함수 계약 |
| [../frontend/screen-specs.md](../frontend/screen-specs.md) | 화면별 기능 명세 |
| [../frontend/feature-spec.md](../frontend/feature-spec.md) | 전체 기능 명세 |

---

## 1. 먼저 확정이 필요한 사항

구현을 시작하기 전에 합의가 필요한 항목입니다. **1~3번은 프론트 화면 작업에 직접
영향을 줍니다.**

| # | 항목 | 현황 | 프론트 의견 |
| --- | --- | --- | --- |
| 1 | **카카오 로그인 설정** | **확정·구현됨** — 백엔드가 카카오 리다이렉트를 직접 받습니다(Spring Security `oauth2Login`). Redirect URI는 로컬 `http://localhost:8080/login/oauth2/code/kakao`(로컬 Compose의 Caddy를 거치면 `http://localhost/login/oauth2/code/kakao`), 운영 `https://iitda.duckdns.org/login/oauth2/code/kakao`. 동의 항목은 현재 `profile_nickname`만 요청합니다(§2.4) | 보호자 연결은 초대 코드 방식으로 확정돼(§5.1) 전화번호 동의 항목은 필요 없습니다. 기관 로그인(I-01)과 보호자 진입(P-01) 화면은 프론트가 재작업합니다 |
| 2 | **세션 전달 방식** | **확정·구현됨** — `access_token` httpOnly 쿠키(1시간). 쓰기 요청은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보냅니다. refresh token은 없습니다 | - |
| 3 | **필드 네이밍** | 프론트 타입은 camelCase, 일부 요청 바디는 snake_case | **camelCase 통일 제안** (Jackson 기본값과도 맞음). 이 문서는 전부 camelCase로 적었습니다. 프론트의 snake_case 요청 바디 3곳은 프론트가 수정합니다 |
| 4 | **보호자–아이 연결 방식** | **확정·구현됨 (10.12 변경)** — **초대 코드** 방식입니다. 기관이 아이를 등록하면 10분짜리 일회용 코드가 나오고, 기관이 카톡 등으로 직접 보냅니다. 보호자는 가입한 뒤 코드를 입력해 연결됩니다(§5.1). SMS·전화번호 대조는 쓰지 않습니다. 이전의 "대기 중 연결 요청" 방식은 매칭 키를 정할 수 없어 폐기했습니다 | 링크(`/parent/invite?code=…`)로 보내면 로그인·가입 동안 코드를 sessionStorage에 들고 있다가 가입 뒤 G-04를 부르면 됩니다. 링크가 안 열리는 경우를 위해 코드 입력 칸도 남겨 주세요 |
| 4-1 | **역할 구분** | **확정·구현됨** — 카카오 로그인 뒤 **회원가입에서 사용자가 직접 선택**합니다(§2.6). 기관은 기관명·유형·사업자등록번호를 함께 입력하며, 사업자등록번호는 형식만 검사합니다 | 로그인 직후 `/auth/me`의 `signupCompleted`가 `false`면 역할 선택 화면으로 보냅니다. sessionStorage로 역할을 정하던 방식은 이 흐름으로 바꿔야 합니다 |
| 5 | **파이프라인 진행 상태** | 프론트는 `setTimeout` 시뮬레이션 | 폴링 / SSE / WebSocket 중 선택. 폴링이면 권장 주기와 상태 조회 엔드포인트가 필요합니다 |
| 6 | **페이지네이션** | 미정 | 현재 프론트는 전체 조회를 가정합니다. 큐·타임라인·일지 목록에 커서 또는 오프셋 페이징이 필요하면 형식을 정해주세요 |
| 7 | **파일 업로드** | 현재 구현: `POST /api/v1/raw-records` multipart, **파일 1개**(`file`), 최대 **20MB**, 허용 확장자 `csv` · `txt` · `pdf` (§4.3) — **결정됨**(2026-09-29): jpg/png/hwp는 이번 학기 범위 밖으로 제외 | 다중 업로드, `docx` 추가 여부는 여전히 논의 필요(다음 이슈에서 진행 예정) |
| 8 | **RawRecord 응답 형식** | 현재 구현 응답이 프론트 `RawRecord` 타입과 다릅니다 (§3 RawRecord의 "현재 구현" 참고) | 프론트 `RawRecord` 타입(§3.2)과 매핑표 합의 필요 |

### 프론트가 이미 알고 있고 스스로 고칠 부분

- `lib/api.ts`의 `request()`가 `{ result, data }` 래퍼를 벗기지 않고 있습니다 → 프론트 수정.
- 오류 바디에서 `error_code`를 읽고 있습니다. 규약은 `code`입니다 → 프론트 수정.
- 요청 바디 3곳(`edited_content`, `allowed_fields`, `target_institution_ids`)이
  snake_case입니다 → camelCase로 프론트 수정.

**백엔드는 [api-conventions.md](api-conventions.md)대로 구현하면 됩니다.**

---

## 2. 인증 · 인가

### 2.1 공통

- 기본 경로: `/api/v1`
- 보호 API는 인증 실패 시 `401 UNAUTHORIZED`, 역할 불일치 시 `403`. 기관 전용 API(예: 원본 기록)를
  기관 소속이 아닌 회원(보호자)이 호출하면 `403 ORGANIZATION_NOT_ASSIGNED`입니다
- 역할: `PARENT` · `ORGANIZATION` (`ADMIN`은 아직 없음). 역할은 회원가입(§2.6)에서 정해집니다
- 가입을 마치지 않은 회원이 `/auth/me` · `/auth/signup` · `/auth/logout` 외의 API를 호출하면
  `403 SIGNUP_NOT_COMPLETED`입니다. 프론트는 역할 선택 화면으로 보냅니다

### 2.2 세션 조회 — 라우트 가드가 매 진입마다 호출

> **구현 완료.** 아래는 실제 응답입니다.

```http
GET /api/v1/auth/me
```

**가입 미완료** — 카카오 로그인만 하고 역할을 고르지 않은 상태. 정상 응답(`200`)입니다.

```json
{
  "result": "SUCCESS",
  "data": { "userId": "5", "name": "박지현", "signupCompleted": false }
}
```

**가입 완료 — 기관**

```json
{
  "result": "SUCCESS",
  "data": { "role": "org", "userId": "5", "name": "박지현", "institutionId": "3", "signupCompleted": true }
}
```

**가입 완료 — 보호자**

```json
{
  "result": "SUCCESS",
  "data": { "role": "parent", "userId": "5", "name": "박지현", "signupCompleted": true }
}
```

| 필드 | 타입 | 항상 포함 | 설명 |
| --- | --- | --- | --- |
| `signupCompleted` | boolean | 예 | `false`면 가입 미완료. 프론트는 역할 선택 화면으로 보냅니다 |
| `userId` | string | 예 | 내부 PK를 문자열로 직렬화한 값입니다 (`"u_1"` 같은 접두사는 붙지 않습니다) |
| `name` | string | 아니오 | 카카오 닉네임. 동의를 거부하면 **이 필드가 빠집니다** |
| `role` | `"org"` \| `"parent"` | 아니오 | `signupCompleted: true`일 때만. `admin`은 아직 없습니다 |
| `institutionId` | string | 아니오 | `role === "org"`일 때만 |

**프론트는 `signupCompleted`만 보고 분기합니다.** `role`이 없다고 로그인 화면으로 보내면
안 됩니다 — 가입 미완료는 정상 상태입니다.

비로그인 요청에는 `401`을 반환합니다. 프론트는 `401`을 "다시 로그인"으로 해석해
로그인 화면으로 보냅니다. 출입증이 유효하더라도 그 회원이 DB에 없거나 탈퇴했으면(예: subject를
내부 `userId`로 바꾸기 전에 발급된 토큰) 마찬가지로 `401`(`SESSION_USER_NOT_FOUND`)입니다.

### 2.3 로그아웃

```http
POST /api/v1/auth/logout   → 204 No Content
```

> **구현 완료.** 서버가 `access_token` 쿠키를 만료시킵니다. 쓰기 요청이므로
> `X-XSRF-TOKEN` 헤더가 필요하며, 없거나 일치하지 않으면 `403`입니다.

### 2.4 로그인 — 카카오

기관 담당자와 보호자 **모두 카카오 로그인**을 사용합니다.

> 상태: **구현 완료**. 초안의 `POST /api/v1/auth/kakao`(프론트가 인가 코드를 전달하는
> 방식)는 쓰지 않습니다. 인가 코드 교환·state 검증·사용자 조회는 전부 백엔드의
> Spring Security가 처리합니다.

```http
GET /oauth2/authorization/kakao        ← 브라우저 페이지 이동으로 호출 (fetch 아님)
GET /login/oauth2/code/kakao           ← 카카오가 호출하는 콜백. 프론트는 호출하지 않음
```

| 결과 | 동작 |
| --- | --- |
| 성공 | `access_token` httpOnly 쿠키를 심고 `AUTH_SUCCESS_REDIRECT`(기본 `/oauth/success`)로 리다이렉트 |
| 실패 (state 불일치, 동의 취소, 카카오 장애 등) | `AUTH_FAILURE_REDIRECT`(기본 `/login`)에 `?error=login_failed`를 붙여 리다이렉트 |

기관 담당자와 보호자가 **같은 주소**로 로그인합니다. 로그인은 신원 확인만 하고 역할을 정하지
않습니다. 처음 로그인한 사람은 역할 없는 **가입 미완료** 회원으로 등록되고, 역할은
[§2.6 회원가입](#26-회원가입)에서 정해집니다. 이미 가입한 회원의 역할은 다시 로그인해도 바뀌지 않습니다.
(예전의 `?invite=` 파라미터로 역할을 정하던 방식은 폐기했습니다.)

로그인 응답에는 바디가 없습니다. 로그인 직후 상태는
[§2.2 `GET /api/v1/auth/me`](#22-세션-조회--라우트-가드가-매-진입마다-호출)의 `signupCompleted`로 확인합니다.
`termsAgreed`는 아직 없습니다(약관 도메인 미구현).

> §2.5 약관 동의는 아직 구현되지 않았습니다.

### 2.5 서비스 약관 동의

카카오 동의 항목과 별개로, 서비스 약관(필수 2 · 선택 1)은 자체 저장이 필요합니다(P-01).

```http
POST /api/v1/auth/terms
{ "agreed": ["tos", "privacy", "notify"] }
```

| 키 | 라벨 | 필수 |
| --- | --- | --- |
| `tos` | 서비스 이용약관 동의 | 예 |
| `privacy` | 개인정보와 민감정보 수집 동의 | 예 |
| `notify` | 새로운 소식 알림 받기 | 아니오 |

필수 약관에 동의하지 않은 사용자가 보호 API를 호출하면 `403 TERMS_NOT_AGREED`를
반환해주세요. 프론트가 약관 화면으로 되돌립니다.

### 2.6 회원가입

> **구현 완료.** 이메일·비밀번호 가입 폼은 없습니다. 카카오 로그인 → 역할 선택 → (기관이면)
> 기관 정보 입력까지가 회원가입입니다.

```http
POST /api/v1/auth/signup   → 201 Created
```

출입증 쿠키가 필요하고(카카오 로그인이 끝난 상태), 쓰기 요청이므로 `X-XSRF-TOKEN` 헤더도 필요합니다.

**요청 — 보호자**

```json
{ "role": "parent" }
```

**요청 — 기관**

```json
{
  "role": "org",
  "organizationName": "햇살아동발달센터",
  "organizationType": "CENTER",
  "businessNumber": "1234567890"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `role` | `"org"` \| `"parent"` | 예 | |
| `organizationName` | string (최대 100자) | `role === "org"`일 때 | 기관명. 같은 이름의 기관이 여러 곳 있어도 됩니다 |
| `organizationType` | `"SCHOOL"` \| `"CENTER"` \| `"ACTIVITY_SUPPORT"` | `role === "org"`일 때 | 백엔드 enum 값 그대로. 화면 표시용 변환은 프론트 몫입니다 |
| `businessNumber` | string (`^\d{10}$`) | `role === "org"`일 때 | 사업자등록번호. 하이픈 없이 숫자 10자리. **형식만 검사하고 진위·체크섬은 검증하지 않습니다** |

보호자는 `role`**만** 보냅니다. 기관 필드가 하나라도 섞이면(값이 맞든 틀리든) `400`입니다.
기관 필드는 **아예 빼거나 `null`로** 보내 주세요. 빈 문자열 `""`도 보낸 값으로 보고 `400`입니다 —
가입 화면에서 기관 입력란에 쓰다가 보호자로 바꿨다면, 폼의 `""` 값을 그대로 싣지 말고 필드를 빼 주세요.
아이 연결은 가입과 별개입니다.

**응답 — `201 Created`.** 본문은 §2.2의 가입 완료 응답과 같습니다.

```json
{
  "result": "SUCCESS",
  "data": { "role": "org", "userId": "5", "name": "박지현", "institutionId": "3", "signupCompleted": true }
}
```

**처리 규칙**

1. 기관이면 입력한 정보로 기관을 새로 만들고 그 기관에 소속시킵니다 (기관 1곳당 계정 1개).
2. 이미 가입을 마친 회원이 다시 호출하면 `409 ALREADY_SIGNED_UP`이고 역할은 바뀌지 않습니다.
   멱등하지 않으므로 성공 후 재시도 버튼을 두지 않습니다.
3. 이미 등록된 사업자등록번호면 `409 DUPLICATE_BUSINESS_NUMBER`입니다.

| 상태 | 코드 | 조건 | 프론트 동작 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | `role` 누락·잘못된 값, 기관인데 기관 필드 누락, 보호자인데 기관 필드 포함, `businessNumber` 형식 오류, 없는 `organizationType` | 입력 오류 표시 |
| 401 | `UNAUTHORIZED` · `SESSION_USER_NOT_FOUND` | 비로그인 · 없는 회원 | 로그인 화면으로 |
| 403 | `FORBIDDEN` | CSRF 토큰 누락 또는 불일치 | |
| 409 | `ALREADY_SIGNED_UP` | 이미 가입 완료 | `/auth/me`를 다시 불러 역할별 홈으로 |
| 409 | `DUPLICATE_BUSINESS_NUMBER` | 이미 등록된 사업자등록번호 | "이미 등록된 사업자등록번호입니다" 표시, 재입력 유도 |

---

## 3. 공통 모델

응답 `data` 안에서 반복되는 객체입니다. 프론트 타입 정의는
[feature-interfaces.md](../frontend/feature-interfaces.md) §2와 동일합니다.

### Institution

```json
{ "id": "2", "name": "햇살아동발달센터", "type": "center", "verified": true }
```

`type`: `school` · `center` · `assistant`

### Child

```json
{
  "id": "1",
  "name": "김하늘",
  "birthDate": "2017-03-14",
  "school": "○○초 2학년",
  "status": "active",
  "institutions": [
    { "institution": { "id": "1", "name": "○○초등학교", "type": "school", "verified": true },
      "consent": "granted" }
  ],
  "care": {
    "welfareCard": true,
    "allergies": ["우유와 유제품"],
    "medications": [{ "name": "○○정", "time": "점심 식후" }],
    "weeklySchedule": [{ "day": "월", "note": "센터 15:00" }]
  }
}
```

`status`: `pending_consent` · `active` · `suspended`
`consent`: `granted` · `not_granted` · `revoked`

### RawRecord

```json
{
  "id": "1",
  "fileName": "0821_관찰일지.docx",
  "type": "관찰일지",
  "capturedAt": "2026-08-21T11:40:00+09:00",
  "preview": "오늘 오전 활동 중…"
}
```

`type`: `관찰일지` · `활동일지` · `특이사항`
(한국어 고정값을 그대로 쓸지, enum 코드값 + 표시명 분리로 갈지 의견 주세요.)

> `사진`은 뺐습니다(#72) — 이미지 업로드 자체가 이번 학기 범위 밖으로 제외됐습니다(OCR 필요,
> 이슈 #67). `RecordResponse`에는 애초에 `type`이 없어서 BE 구현과 지금 당장 어긋나는 부분은
> 없습니다.

**현재 구현** (`POST/GET /api/v1/raw-records`) · 위 형식과 다릅니다. 매핑은 §1-8에서 합의합니다.

```json
{
  "id": 1,
  "institutionId": "3",
  "originalFilename": "0821_관찰일지.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 20480,
  "status": "PENDING",
  "createdAt": "2026-08-21T11:40:00"
}
```

| 필드 | 초안과 차이 |
| --- | --- |
| `id` | 숫자 (문자열 규칙의 예외 — api-conventions.md 참고) |
| `institutionId` | 업로드한 기관 회원의 소속 기관 ID (문자열) |
| `originalFilename` | 초안의 `fileName` |
| `status` | `PENDING` · `REVIEW` · `BLOCKED` · `FAILED` (파이프라인 단계 §4.3 O-21과 별개) |
| `createdAt` | 서버 저장 시각. 현재 **시간대 오프셋이 없습니다** |
| `type` · `capturedAt` · `preview` | 없음 |

### 날짜 형식

| 용도 | 형식 | 예 |
| --- | --- | --- |
| 날짜 | `YYYY-MM-DD` | `2026-08-21` |
| 시각 | `HH:mm` | `14:30` |
| 타임스탬프 | ISO 8601 (KST offset 포함) | `2026-08-21T11:40:00+09:00` |

---

## 4. 기관 API

모두 `role === ORGANIZATION` 필요. 기관 스코프는 세션의 `institutionId`를 사용합니다
(경로의 `me`).

### 4.1 기관 정보

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-01 | GET | `/institutions/me` | 내 기관 정보 | I-02 |
| O-02 | PATCH | `/institutions/me` | 기관명 · 유형 수정 | I-02 |
| O-03 | POST | `/institutions/me/documents` | 증빙서류 업로드 (multipart) | I-02 |

**O-03 요청** · `multipart/form-data`, 필드 `file`
**O-03 응답** · `{ "fileName": "인가증_2026.pdf", "status": "reviewing" }`
(`status`: `verified` · `reviewing` · `rejected`)

### 4.2 아동

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-10 | GET | `/institutions/me/children` | 담당 아동 목록 · **구현됨** | I-05, I-09, I-13 |
| O-11 | POST | `/institutions/me/children` | 아이 등록 + 초대 코드 발급 · **구현됨** | I-04 |
| O-13 | GET | `/children/{childId}` | 아동 상세 | I-09-1 |
| O-14 | GET | `/children/{childId}/context` | Child Context 타임라인 | I-09-1 |
| O-15 | POST | `/institutions/me/children/{childId}/invitations` | 초대 코드 추가 발급 · **구현됨** | I-04, I-09-1 |
| O-16 | GET | `/institutions/me/children/{childId}/invitations` | 쓸 수 있는 초대 코드 목록 · **구현됨** | I-04, I-09-1 |
| O-17 | DELETE | `/institutions/me/children/{childId}/invitations/{invitationId}` | 초대 코드 취소 · **구현됨** | I-04, I-09-1 |
| O-18 | GET | `/institutions/me/children/{childId}/guardians` | 연결된 보호자 목록 · **구현됨** | I-09-1 |
| O-19 | DELETE | `/institutions/me/children/{childId}/guardians/{guardianUserId}` | 보호자 연결 해제 · **구현됨** | I-09-1 |

> 이전 초안에 있던 `O-12 초대코드 재발급`은 결번으로 둡니다. 재발급은 "취소(O-17) 후 새로 발급(O-15)"입니다.

**O-10 응답** · RosterPicker(매칭 확인 · Gate 1 아동 변경)가 쓰는 필드와 기관 관리번호만 우선 구현했습니다.

```json
[
  { "id": "9", "name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031", "status": "active" },
  { "id": "10", "name": "임유진", "birthDate": "2019-11-26", "externalId": null, "status": "pending_consent" }
]
```

`externalId`는 이 기관이 붙인 관리번호입니다(O-11). 등록할 때 필수지만, 필수가 되기 전에 등록된 아이는 번호가 없어 `null`입니다.

`school`/`institutions`/`care`는 아직 없습니다 — `Child` 엔티티에 그 컬럼 자체가 없습니다(O-11 구현에서도 채우지 않았고, 별도 작업입니다). `status`가 `active`가 아닌 아동도 포함해서 내려갑니다 — 필터링은 프론트 몫입니다(`RosterPicker.tsx`가 이미 그렇게 함).

**O-11 요청**

```json
{ "name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031" }
```

- `name`: 필수. 앞뒤 공백을 떼고 1–100자입니다.
- `birthDate`: 필수. **`yyyy-MM-dd` 문자열만** 받습니다. `"2017-03-14T00:00:00"`, `[2017, 3, 14]`, 숫자,
  없는 날짜(`"2017-02-30"`), 미래 날짜는 400입니다. 하한(나이 상한)은 두지 않습니다.
- `externalId`: **필수.** 기관이 쓰는 아동 관리번호입니다. 앞뒤 공백을 떼고 1–50자이며, 형식은 자유입니다.
  없거나 빈 문자열·공백뿐이면 400입니다. **같은 기관 안에서는 겹칠 수 없습니다**(409).
  다른 기관은 같은 번호를 써도 됩니다. 아이를 명부에서 빼면 그 번호는 다시 쓸 수 있습니다.
- 같은 이름·생일로 다시 등록해도 막지 않고 새 아동을 만듭니다. 다른 기관이 같은 아이를 등록해도 별도 아동(`id`)이 됩니다.

> 이전 초안의 `guardianPhoneLast4`는 받지 않습니다. 보내도 무시합니다. 보호자 전화번호 전체도 받지 않습니다 —
> 보호자 연결은 기관이 초대 코드를 직접 전달하는 방식입니다(§5.1).

**O-11 응답** · `201 Created`

```json
{
  "child": { "id": "9", "name": "김하늘", "birthDate": "2017-03-14",
             "externalId": "2026-0031", "status": "pending_consent" },
  "invitation": { "id": "31", "code": "K7P3QX9M", "expiresAt": "2026-10-12T10:10:00+09:00" }
}
```

- `invitation`: 등록과 함께 발급한 보호자 초대 코드입니다. 아이 등록과 한 트랜잭션이라 코드를 못 만들면 아이도 등록되지 않습니다(503).
- `code`: 하이픈 없는 8자(대문자·숫자, `0 O 1 I L` 제외)입니다. 화면에는 `K7P3-QX9M`처럼 4-4로 나눠 보여 주세요.
- `expiresAt`: 발급 후 **10분**. 시간대 오프셋이 붙어 있으니 브라우저 시계와 비교해 카운트다운하면 됩니다.

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 이름 누락·공백·100자 초과, 생년월일 누락·형식 오류·없는 날짜·미래 날짜, 관리번호 50자 초과 |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입(역할 선택)을 끝내지 않음 |
| 403 | `ORGANIZATION_NOT_ASSIGNED` | 기관 소속이 아닌 사용자(보호자) |
| 403 | `FORBIDDEN` | CSRF 토큰 누락·불일치 |
| 409 | `DUPLICATE_EXTERNAL_ID` | 같은 기관에서 이미 쓰고 있는 관리번호 |
| 503 | `INVITE_CODE_ISSUE_FAILED` | 동시에 발급된 코드와 겹침(거의 일어나지 않음). 아이도 등록되지 않았으니 다시 요청 |

등록 직후 아이는 `pending_consent` 상태입니다. 보호자가 초대 코드로 연결(G-04)하고 공유 범위에 동의해야
`active`가 되며, 그 전에는 기록을 올릴 수 없고 AI 매칭 명단에도 들어가지 않습니다.
**연결만으로는 `active`가 되지 않습니다** — 동의는 별도 단계입니다.

**O-15 응답** · `201 Created` · 우리 기관 명부의 아이에게 코드를 하나 더 발급합니다. 한 아이에 쓸 수 있는 코드가
여러 개 있어도 됩니다(엄마·아빠에게 따로 보내기). 응답은 O-11의 `invitation`과 같습니다.

```json
{ "id": "32", "code": "M4RZ8WTB", "expiresAt": "2026-10-12T10:25:00+09:00" }
```

**O-16 응답** · 아직 쓸 수 있는 코드만(수락·취소·만료 제외), 최근 발급 순입니다. 없으면 빈 배열입니다.

```json
[
  { "id": "32", "code": "M4RZ8WTB", "issuedAt": "2026-10-12T10:15:00+09:00", "expiresAt": "2026-10-12T10:25:00+09:00" }
]
```

**O-17** · `204 No Content` · 아직 쓸 수 있는 코드를 거둬들입니다. 이미 수락·취소·만료된 코드는 `404 INVITATION_NOT_FOUND`입니다.

**O-18 응답** · 이 아이와 연결된 보호자 전원입니다. 다른 기관을 통해 연결된 보호자도 나오지만,
`linkedByThisOrganization`이 `true`인 보호자만 O-19로 해제할 수 있습니다. `name`은 카카오 닉네임이라 없으면 `null`입니다.

```json
[
  { "userId": "41", "name": "김보호", "linkedAt": "2026-10-12T10:03:00+09:00", "linkedByThisOrganization": true }
]
```

**O-19** · `204 No Content` · 우리 기관이 발급한 코드로 생긴 연결을 끊습니다. 연결은 기관 구분 없는 전역 연결이라
**끊으면 모든 기관에서 끊깁니다.** 잘못된 사람이 코드로 들어왔을 때 쓰는 기능입니다.

O-15 ~ O-19 공통 오류:

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입(역할 선택)을 끝내지 않음 |
| 403 | `ORGANIZATION_NOT_ASSIGNED` | 기관 소속이 아닌 사용자(보호자) |
| 403 | `FORBIDDEN` | CSRF 토큰 누락·불일치 (POST·DELETE) |
| 403 | `GUARDIAN_LINKED_BY_OTHER_ORGANIZATION` | (O-19) 다른 기관 코드로 연결된 보호자 |
| 404 | `CHILD_NOT_FOUND` | 없거나 삭제됐거나 **우리 기관 명부에 없는** 아이. 다른 기관 아이도 같은 404입니다 |
| 404 | `INVITATION_NOT_FOUND` | (O-17) 없거나 이미 쓸 수 없는 코드 |
| 404 | `GUARDIAN_NOT_FOUND` | (O-19) 이 아이와 연결되지 않은 보호자 |
| 503 | `INVITE_CODE_ISSUE_FAILED` | (O-15) 동시에 발급된 코드와 겹침. 다시 요청 |

**O-14 응답** · 날짜 오름차순. **기록이 없는 날도 항목으로 포함하고 `entry`를 `null`로**
주세요. 프론트는 빈 날을 "기록 없음"으로 표시하며, 추정치로 채우지 않습니다.

```json
[
  { "date": "2026-08-19", "entry": null },
  { "date": "2026-08-20", "entry": {
      "recordType": "관찰일지", "validation": "PASS",
      "content": "오전 활동에서 …", "sourceCount": 2, "version": 2, "edited": true } }
]
```

### 4.3 기록 등록 · 큐

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-20 | POST | `/raw-records` | 원본 기록 업로드 (multipart) | I-05 |
| O-21 | GET | `/raw-records/{id}/status` | 파이프라인 진행 상태 | I-03, I-05 |
| O-22 | GET | `/matching-queue` | 확인 필요 큐 · **구현됨** | I-03, I-06 |
| O-23 | POST | `/matching-queue/{itemId}/resolve` | 아이 확정 / 제외 · **구현됨** | I-06 |
| O-24 | GET | `/validation-results?status=BLOCK` | 수정 요청 큐 · **구현됨** | I-03, I-07 |
| O-25 | POST | `/validation-results/{itemId}/resolve` | 재업로드 / 보류 · **구현됨** | I-07 |
| O-26 | GET | `/raw-records/progress` | 파일별 처리 현황 · **계약 확정, 구현 예정** | I-03, I-05 |
| O-27 | POST | `/raw-records/{id}/retry` | 실패한 기록 재처리 · **계약 확정, 구현 예정** | I-03, I-05 |

**O-20 요청** · `multipart/form-data`

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `file` | 예 | 다중 업로드 지원 여부 회신 필요 |

아이 · 기록 유형 · 기록 시각은 받지 않습니다. 파일에는 여러 아이의 기록이 섞여 있어
아이는 매칭이 판정하고, 기록 시각은 파일 본문에서 추출합니다. 아이를 확정해서 넣는
경로는 직접 입력(`POST /journal-entries`, 신규 예정)으로 분리합니다.

- `pending_consent` 상태 아이의 기록은 **거부**해주세요 →
  `409 CHILD_CONSENT_PENDING`
- 용량 초과는 `413 FILE_TOO_LARGE`

> **현재 구현** · `file` 한 개만 받습니다.
>
> | 항목 | 값 |
> | --- | --- |
> | 허용 확장자 | `csv` · `txt` · `pdf` (2026-09-29, 이슈 #67로 `jpg`·`jpeg`·`png`·`hwp` 제외 — jpg/png는 OCR 필요, hwp는 자바 파싱이 매우 어려워 이번 학기 범위 밖) |
> | 허용 Content-Type | `text/csv` · `application/vnd.ms-excel`(Windows가 csv에 흔히 붙이는 값, #113) · `text/plain` · `application/pdf` |
> | 기록 분리(텍스트 추출) | `csv`·`txt`만 지원. `pdf`는 업로드는 되지만 아직 `JournalEntry`로 안 쪼개짐(`entries: []` 유지) — 다음 이슈에서 추가 |
> | 최대 크기 | 20MB (Caddy는 25MiB에서 먼저 차단) |
> | 성공 | `201 Created` |
> | 오류 | 확장자·형식·파일명 오류 `400 RAW_RECORD_INVALID_REQUEST`, 용량 초과 `413 RAW_RECORD_FILE_TOO_LARGE`, 저장 실패 `500 RAW_RECORD_STORAGE_FAILED` |
>
> 조회용으로 `GET /raw-records`(내 기관 목록)와 `GET /raw-records/{id}`(단건, 다른 기관 기록은 `404 RAW_RECORD_NOT_FOUND`)가 있습니다.

**O-21 응답** · 파이프라인 단계는 프론트 `PIPELINE_STAGES`와 맞춥니다.

```json
{ "id": "1", "stage": "validating", "stageIndex": 2, "totalStages": 5 }
```

| `stage` | 라벨 |
| --- | --- |
| `uploaded` | 기록 등록 |
| `matching` | 매칭 |
| `validating` | 검증 |
| `summarizing` | 요약 |
| `gate1_pending` | 1차 검토 대기 |

**O-22 응답** · `status`가 `auto`인 건은 포함하지 않습니다.

> `record.fullContent`를 추가했습니다(멘토 PR #86 리뷰 반영, #111) — 목록의 `preview`(60자)는
> 그대로 두고, 아이를 선택할 때 펼쳐 볼 전체 본문을 별도 필드로 내려줍니다. 본문 뒷부분에
> 나오는 아이 이름이나 AI 판단 근거를 선생님이 놓치지 않기 위함입니다. `evidence`의
> `{start, end}`는 `preview`가 아니라 `fullContent` 기준 유니코드 코드포인트 좌표입니다.

```json
[
  { "id": "1",
    "journalEntryId": 7,
    "matchedChildId": null,
    "status": "multi",
    "confidence": 0.62,
    "multiReason": "ambiguous_identity",
    "hintMismatch": false,
    "record": { "id": "3", "fileName": "0821_활동일지.docx",
                "preview": "…", "capturedAt": "2026-08-21", "fullContent": "…" },
    "candidates": [ { "childId": "1", "name": "김하늘", "birthDate": "2020-01-01", "confidence": 0.62 } ],
    "evidence": [ { "start": 12, "end": 15 } ] }
]
```

- `id`·`record.id`·`candidates[].childId`·`matchedChildId`는 문자열, `journalEntryId`는 숫자입니다 (api-conventions.md의 PK 규칙 참고)
- `journalEntryId`: 이 결과가 가리키는 기록(`journal_entry`)의 ID
- `matchedChildId`: 확정된 아동 ID. 큐에 남아 있는 항목은 아직 확정 전이라 보통 `null`입니다
- 값이 없는 필드(`matchedChildId`·`multiReason`·`evidence` 등)는 생략되지 않고 `null`로 내려갑니다

- `status`: `auto` · `review` · `multi` · `unmatched` · `failed` — AI 매칭 에이전트 실제 계약(`AI/matching/schemas.py`)값 그대로 (9/22 팀 확인, 9/24 DB초안 반영). `failed`는 AI 호출 자체가 실패했을 때만 쓰는 BE 전용 값
- `confidence`: 0.0~1.0, 항상 채워짐 (AI 계약 기본값 0.0) — `multi`면 후보 중 최고점과 같음
- `multiReason`: `status`가 `multi`일 때만 채워짐 — `ambiguous_identity` · `co_mention`
- `hintMismatch`: 표지 힌트와 다른 아동으로 판단했는지
- `evidence`: 판정 근거가 된 본문 구간(`{start, end}`, 유니코드 코드포인트 인덱스)
- `record`/`candidates[].{name,birthDate}`은 이제 JournalEntry·Child 조회로 채워서 내려갑니다(PR #35, feat/be/#31).
  다만 **`record.type`(기록 유형)과 `candidates[].group`(반)은 DB에 그 값을 담을 컬럼 자체가 없어서 여전히 못 채웁니다** —
  `RawRecord`엔 기록 유형 컬럼이, `Child`엔 반 컬럼이 없습니다. 프론트 `types.ts`의 `RawRecord.type`/후보 `group`은 당장은 빈 값으로 와야 합니다.
- 프론트 `types.ts`는 "화면에 숫자를 띄우면 교사가 근거로 삼게 된다"는 이유로 `confidence`를 일부러 타입에서 뺐는데,
  BE는 위와 같이 최상위/후보별 `confidence`를 그대로 내려줍니다. 화면에서 안 쓰면 되지만, 필드 자체는 내려간다는 점 확인해주세요.

**O-23 요청** · 프론트는 선택한 아이를 함께 보냅니다.

```json
{ "action": "assign", "childId": "1" }
```

| `action` | 의미 | `childId` |
| --- | --- | --- |
| `assign` | 선택한 아이로 확정 | 필수 |
| `not_ours` | 우리 기관 아동 아님 (제외) | 없음 |

> 프론트 `resolveMatchingItem(id, resolution)`이 이 형식으로 보냅니다. 후보나 명부에서 아이를
> 고르면 `assign`, "이 기관 아동 아님"을 누르면 `not_ours`입니다.

처리하면 기록(`journal_entry`)도 함께 바뀝니다 — `assign`은 검증 단계로 넘어가고, `not_ours`는 제외돼 여기서 끝납니다 (DB 스키마 §6.2).
확인 필요 큐에는 아직 처리되지 않은 기록만 나옵니다.
아래 경우는 `400 MATCHING_RESULT_INVALID_REQUEST`로 거절합니다.

- `assign`인데 `childId`가 없거나, 없는 아동이거나, 이 기관 소속이 아님
- `assign`으로 고른 아동이 **동의 완료(`active`) 상태가 아님** — AI 매칭 명단과 같은 기준
- 이미 처리된 기록 (다른 선생님이 먼저 처리한 경우 등. 두 명이 거의 동시에 누르는 경우까지 막지는 않습니다)

`childId`는 O-22의 `candidates[].childId`·O-10의 `id` 문자열을 그대로 보내면 됩니다(`"1"`, 숫자 `1`도 받습니다).
`"child_1"`처럼 숫자로 읽을 수 없는 값은 `400 INVALID_REQUEST`입니다.

**O-23 응답** · 처리한 항목을 O-22 항목과 같은 형식으로 돌려줍니다. `200 OK`.

```json
{ "id": "1",
  "journalEntryId": 7,
  "matchedChildId": "1",
  "status": "auto",
  "confidence": 0.62,
  "multiReason": "ambiguous_identity",
  "hintMismatch": false,
  "record": { "id": "3", "fileName": "0821_활동일지.docx",
              "preview": "…", "capturedAt": "2026-08-21" },
  "candidates": [ { "childId": "1", "name": "김하늘", "birthDate": "2020-01-01", "confidence": 0.62 } ],
  "evidence": [ { "start": 12, "end": 15 } ] }
```

- 처리하면 `status`는 `auto`가 됩니다. AI 계약에 "사람이 확정함"을 뜻하는 값이 따로 없어서, 사람이 처리한 경우도 "더 이상 확인할 필요 없음"이라는 뜻으로 `auto`를 씁니다
- `matchedChildId`: `assign`이면 고른 아동 ID, `not_ours`이면 `null`
- 나머지 필드(`confidence`·`candidates` 등)는 AI 판정 값 그대로입니다

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `MATCHING_RESULT_INVALID_REQUEST` | 위 거절 조건, 지원하지 않는 `action` |
| 400 | `INVALID_REQUEST` | 본문이 JSON이 아니거나 `childId`를 숫자로 읽을 수 없음 |
| 403 | `ORGANIZATION_NOT_ASSIGNED` | 기관 회원이 아님 |
| 403 | `FORBIDDEN` | `X-XSRF-TOKEN` 헤더 누락·불일치 (§1-2) |
| 404 | `MATCHING_RESULT_NOT_FOUND` | 없는 항목이거나 다른 기관의 항목 |

**O-24 응답**

```json
[
  { "id": "1",
    "record": { "id": "5", "fileName": "0821_특이사항.txt", "type": "특이사항",
                "capturedAt": "2026-08-21T16:00:00+09:00", "preview": "…" },
    "childName": "김하늘",
    "violationReason": "다른 아이의 이름이 함께 적혀 있습니다" }
]
```

`violationReason`은 **화면에 그대로 노출**되므로 선생님이 읽고 바로 조치할 수 있는
한국어 문장으로 주세요. 코드값이 필요하면 `violationCode`를 별도로 추가해주세요.

> **현재 구현** (#122) · `status`는 `BLOCK`만 받습니다. 다른 값은 `400`으로 거절합니다 —
> 조용히 BLOCK으로 처리하면 화면이 엉뚱한 목록을 보여주기 때문입니다.
>
> ```json
> [
>   { "id": "11",
>     "journalEntryId": 7,
>     "record": { "id": "5", "fileName": "0821_특이사항.txt",
>                 "capturedAt": "2026-08-21", "preview": "…", "fullContent": "…" },
>     "childName": "김하늘",
>     "violationReason": "전화번호·주민번호 같은 개인정보가 그대로 적혀 있습니다",
>     "violationCode": ["개인정보표현"] }
> ]
> ```
>
> | 항목 | 값 |
> | --- | --- |
> | `violationReason` | AI가 보낸 `issue_types`를 선생님이 읽을 문장으로 바꾼 값. 유형이 여러 개면 ` / `로 잇습니다. AI가 목록에 없는 유형을 보내면 코드를 그대로 보여주고, 유형 자체가 없으면 "검증에서 문제가 발견됐습니다. 원본을 확인해주세요"로 내려갑니다 |
> | `violationCode` | AI `issue_types` 원본 배열 (`진단명`·`개인정보표현` 등, `AI/validation/config.py`) |
> | `record.fullContent` | 위반 문장이 60자 뒤에 있으면 미리보기만으로는 무엇을 고쳐야 할지 알 수 없어, 매칭 확인 큐(#111)와 같은 이유로 전체 본문도 함께 내려갑니다 |
> | `record.type` | **내려가지 않습니다.** `RecordResponse`에 `type` 필드가 없습니다 — 기록 유형 값 목록이 아직 팀에서 안 정해졌습니다 |
> | `capturedAt` | 기록 날짜(`YYYY-MM-DD`)입니다. 초안의 타임스탬프와 다릅니다 |
>
> 🙏 **FE 쪽에 두 가지 부탁드립니다.**
>
> 1. `BlockedItem.record` 가 `RawRecord` 타입이라 `type` 이 필수인데 BE 가 그 값을 주지 않습니다.
>    `MatchingItem.record` 가 이미 `type?: RawRecord["type"] | null` 로 인라인 정의해 피해 간 것과
>    같은 처리가 필요합니다. 지금 `queue-reinput.tsx` 는 `{item.record.type}` 을 그대로 렌더해서,
>    실연동으로 바꾸면 `"김하늘 · "` 뒤가 비는 가운뎃점만 남습니다(mock 에는 값이 있어 안 보입니다).
> 2. `record.fullContent` 를 `BlockedItem` 에 추가하고 화면에서 써 주세요. 지금은 `preview`(60자)만
>    렌더하고 있어서, 위반 문장이 뒤쪽에 있으면 선생님이 무엇을 고쳐야 할지 볼 수 없습니다 —
>    매칭 확인 큐에서 #111 → #104 로 함께 고쳤던 것과 같은 건입니다.
>
> **O-25 현재 구현** · `{ "action": "reupload" }` 또는 `{ "action": "hold" }`. 둘 다 그 일지의
> 파이프라인을 여기서 끝냅니다(`REUPLOAD_REQUESTED` · `VALIDATION_HELD`). 원본 파일은 고치지도
> 지우지도 않습니다 — 고친 내용은 새 파일로 올라옵니다(append-only).
>
> | 상태 | 코드 | 설명 |
> | --- | --- | --- |
> | 400 | `VALIDATION_RESULT_INVALID_REQUEST` | `action` 누락·오타, BLOCK이 아닌 결과, 이미 처리된 기록 |
> | 404 | `VALIDATION_RESULT_NOT_FOUND` | 없는 항목이거나 다른 기관의 항목 |

**O-25 요청** · `{ "action": "reupload" }` 또는 `{ "action": "hold" }`

**O-26 응답** · 계약 확정, 구현 예정. 최근 업로드가 먼저 옵니다.

```json
[
  { "rawRecordId": "1", "fileName": "0821_관찰일지.docx",
    "uploadedAt": "2026-08-21T11:40:00+09:00",
    "entries": [ { "id": "1", "stageIndex": 1, "state": "waiting" } ] }
]
```

`state`: `running` 자동 진행 중 · `waiting` 사람 확인 대기 · `failed` 시스템 오류 · `done` 완료

> 파일을 기록 단위로 나누는 작업이 끝나기 전까지는 `entries: []` 만 내려옵니다.
> 화면은 "기록 등록 중"에 머무는데, 당분간 이 상태가 정상입니다.

**O-27** · 계약 확정, 구현 예정. 해당 파일에서 `failed` 인 기록만 다시 처리합니다. 본문은 없습니다.

### 4.4 Gate 1 — 1차 검토

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-30 | GET | `/summaries?gate1Status=pending\|hold` | 검토 대기 목록 · 보류함 · **초안 구현** | I-03, I-08 |
| O-31 | POST | `/summaries/{summaryId}/gate1` | 승인 / 수정 후 승인 / 보류 / 제거 · **구현됨** | I-08 |
| O-32 | POST | `/summaries/run` | 지금 요약 · **구현됨** | I-08 |
| O-33 | POST | `/summaries/{summaryId}/reopen` | 보류 해제 · **구현됨** | I-08 |

> O-30 · O-31 · O-33 의 응답 모양은 와이어프레임 합의 전 초안입니다(#178). 화면이 정해지면 #183 에서 확정합니다.

**O-32 요청**

요약은 보통 `entry_date` 다음 날 03:00 에 아동 × 날짜 × 기관 묶음으로 자동으로 만들어집니다
(`docs/db/schema.md` §8.2). 이 요청은 그 마감을 기다리지 않고 요약하게 합니다. 기관은 요청한 선생님의 기관입니다.

```json
{ "childId": 8, "entryDate": "2026-10-08" }
```

응답 `data` 는 `null` 입니다. 요약은 곧이어 워커가 만들고, 같은 날짜에 매칭·검증 중인 기록이 있으면 그것이 끝난 뒤에
만듭니다. 그래서 업로드 직후에 눌러도 됩니다. 요청은 그 날짜의 마감(다음 날 03:00)까지만 유효합니다 — 그 뒤에는 어차피
자동으로 요약됩니다. 결과는 O-30 에서 확인합니다.

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| `childId`·`entryDate` 누락, 날짜 형식 오류 | 400 | `INVALID_REQUEST` |
| 그 아이·날짜에 요약할 기록도, 매칭·검증 중인 기록도 없음 | 400 | `SUMMARY_INVALID_REQUEST` |
| 없는 아이이거나 다른 기관 소속 | 404 | `SUMMARY_CHILD_NOT_FOUND` |

**O-30 응답** · 초안

우리 기관의 요약을 오래된 날짜부터 줍니다. `gate1Status` 는 `pending`(기본값, 검토 대기)과 `hold`(보류함)만
받고 다른 값은 `400 SUMMARY_INVALID_REQUEST` 입니다.

```json
[
  { "id": 50, "childId": 8, "childName": "김하늘", "entryDate": "2026-10-08", "revision": 1,
    "status": "GATE1_PENDING",
    "content": "오전 활동에서 블록을 다섯 층까지 쌓았다 …",
    "needsReview": true, "reviewReasons": ["다른아동이름"],
    "uncoveredEntries": [ { "journalEntryId": 1042, "content": "낮잠을 잤다." } ],
    "reviewFlags": [ { "journalEntryId": 1041, "issueTypes": ["추측성표현"] } ] }
]
```

| 필드 | 뜻 |
| --- | --- |
| `needsReview` · `reviewReasons` | 요약 본문에 다른 아이 이름이 남아 공유 전에 봐야 하는지와 그 이유. 막지 않고 경고만 합니다 |
| `uncoveredEntries` | 요약에 안 들어간 일지와 원문. **승인하면 이 일지도 함께 끝납니다** — "요약에 안 들어간 일지 N건"으로 보여주세요 |
| `reviewFlags` | 요약에 들어간 원본 중 검증에서 `REVIEW` 를 받은 일지와 그 사유(`issue_types` 원본) |

`reviewFlags` 는 검증이 짚은 구간이 **요약 문장의 근거 구간과 같은 일지에서 겹칠 때만** 나옵니다. 요약에 안 실린
부분의 문제까지 띄우지 않기 위해서입니다. 검증 사유와 구간이 짝지어져 있지 않아 일지 단위로 판단하고, 겹치면 그 일지의
사유를 모두 줍니다. 검증 구간이 비어 있으면(어디가 문제인지 모름) 줍니다. 하이라이트(`flaggedSpan`)는 하지 않습니다.
사유 단위로 거르는 것은 검증 `issues` 필드가 생긴 뒤입니다(#179).

**O-31 요청**

```json
{ "decision": "approve" }
```

```json
{ "decision": "approve", "editedContent": "선생님이 고친 본문" }
```

```json
{ "decision": "hold" }
```

| 필드 | 조건 |
| --- | --- |
| `decision` | `approve` \| `hold` \| `remove` (반려는 없습니다 — 고쳐서 승인하거나 보류·제거합니다) |
| `editedContent` | `approve` 일 때만. 빈 문자열 불가. 보류·제거에 보내면 400 |

| 결정 | 요약 | 그 요약의 일지 | 기록(`decision`) |
| --- | --- | --- | --- |
| 승인 (`editedContent` 없음 또는 AI 원문과 같음) | 승인 | 요약에 안 들어간 일지까지 모두 완료 | `APPROVED` |
| 고쳐서 승인 (AI 원문과 한 글자라도 다름) | 승인. 고친 본문이 아동 타임라인에 올라감 | 위와 같음 | `CORRECTED` |
| 보류 | 보류함으로 | 함께 보류 (같은 날 새 기록이 와도 묶이지 않음) | `HOLD` |
| 제거 | 제거 | 모두 삭제. **되돌릴 수 없음** | `REMOVED` |

승인과 제거는 되돌릴 수 없으니 확인 팝업을 띄워주세요. 고친 본문은 MVP 에서 다시 검증하지 않습니다.

**응답** · 초안

```json
{ "summaryId": 50, "status": "APPROVED", "decision": "CORRECTED" }
```

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| `decision` 누락·허용되지 않은 값, 빈 `editedContent`, 승인이 아닌데 `editedContent` | 400 | `INVALID_REQUEST` |
| 없는 요약이거나 다른 기관 요약 | 404 | `SUMMARY_NOT_FOUND` |
| 이미 처리된 요약 (검토 대기가 아님) | 409 | `SUMMARY_ALREADY_REVIEWED` |
| 같은 날 새 기록을 반영해 요약을 다시 만드는 중 — 잠시 뒤 다시 시도 | 409 | `SUMMARY_REGENERATING` |

**O-33 보류 해제** · 본문 없음

보류함의 요약과 그 일지를 다시 검토 대기로 돌립니다. 응답은 O-31 과 같은 모양입니다
(`{ "summaryId": 50, "status": "GATE1_PENDING", "decision": "REOPENED" }`).

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| 없는 요약이거나 다른 기관 요약 | 404 | `SUMMARY_NOT_FOUND` |
| 보류된 요약이 아님 | 409 | `SUMMARY_NOT_HELD` |

### 4.5 Insight · Gate 2

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-40 | GET | `/insights` | Insight 목록 | I-03, I-10, I-11 |
| O-41 | GET | `/insights/{id}` | Insight 단건 | I-10 |
| O-42 | POST | `/insights/{insightId}/gate2` | 발송 승인 / 보류 | I-11 |

**O-40 응답**

```json
[
  { "id": "1", "childId": "1", "childName": "김하늘",
    "period": "2026년 8월 3주차",
    "content": "오후 시간대 …",
    "primarySource": { "id": "2", "name": "햇살아동발달센터",
                       "type": "center", "verified": true },
    "evidence": [
      { "childContextId": "10", "date": "2026-08-19", "label": "관찰일지 요약",
        "institution": { "id": "2", "name": "햇살아동발달센터",
                         "type": "center", "verified": true } }
    ],
    "targets": [
      { "institution": { "id": "1", "name": "○○초등학교",
                         "type": "school", "verified": true }, "consent": "granted" },
      { "institution": { "id": "3", "name": "△△활동지원",
                         "type": "assistant", "verified": true }, "consent": "not_granted" }
    ],
    "gate2Status": "pending" }
]
```

- `primarySource` = 근거 요약을 최다 제공한 기관. **이 기관만 Gate 2를 승인할 수
  있습니다.** 서버에서도 같은 조건으로 인가해주세요 (`403 NOT_PRIMARY_SOURCE`).
- `targets`에는 **미동의 기관도 `consent`와 함께 포함**해주세요. 프론트는 미동의
  기관을 목록에서 숨기지 않고 **잠금 + 사유 표시**로 보여줍니다.

**O-42 요청**

```json
{ "decision": "approve", "targetInstitutionIds": ["1"] }
```

| 규칙 | 내용 |
| --- | --- |
| 기본값 | 프론트는 아무 기관도 선택하지 않은 상태로 시작합니다 |
| 빈 배열 | `approve` + 빈 배열이면 프론트가 호출 자체를 하지 않습니다. 서버에서도 `400 INVALID_REQUEST`로 막아주세요 |
| 미동의 기관 | `granted`가 아닌 기관 id가 오면 `403 CONSENT_NOT_GRANTED` |
| 되돌리기 | **발송은 취소할 수 없습니다.** 발송 취소 API는 만들지 않습니다 |

**응답** · `{ "gate2Status": "approved" }`
발송 대상 기관별로 **동의 범위에 맞게 변환된 최소 정보**만 저장·전달해주세요.

### 4.6 수신함 · 상담 지원 · 감사 로그

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-50 | GET | `/institutions/me/inbox` | 수신함 | I-12 |
| O-51 | POST | `/institutions/me/inbox/{id}/read` | 읽음 처리 | I-12 |
| O-52 | POST | `/children/{childId}/chat` | 상담 지원 질의 | I-13 |
| O-53 | GET | `/audit-logs` | 업무 기록 | I-14 |

**O-50 응답** · `content`는 **원본이 아니라 수신자 기준으로 변환된 최소 정보**입니다.

```json
[ { "id": "1",
    "from": { "id": "1", "name": "○○초등학교", "type": "school", "verified": true },
    "childName": "김하늘", "receivedAt": "2026-08-21 15:20",
    "content": "이동 전 5분 예고가 효과적이었습니다.", "read": false } ]
```

**O-52 요청** · `{ "question": "최근 2주 동안 잘 지냈나요?" }`

**O-52 응답**

```json
{ "question": "최근 2주 동안 잘 지냈나요?",
  "answer": "8월 셋째 주 관찰 기록에서는 …",
  "sources": [ { "date": "2026-08-19", "label": "관찰일지 요약" } ] }
```

| 규칙 | 내용 |
| --- | --- |
| 근거 없음 | `answer`를 `null`, `sources`를 `[]`로 주세요. 프론트가 "확인된 기록에는 없어요."로 표시합니다. **문장을 생성하지 마세요** |
| 근거 범위 | **Gate 1에서 승인된 요약만** 검색 대상입니다. 검증 전 원본은 제외 |
| 출처 | 답변이 있으면 `sources`는 **반드시 1건 이상** |

**O-53 응답** · `[{ "id", "at", "actor", "action", "target" }]`
승인 · 발송 · 조회 행위를 모두 기록해주세요.

---

## 5. 보호자 API

`role === PARENT` 필요. **모든 아이별 조회는 `childId`를 경로 또는 쿼리로 받습니다.**
서버가 "현재 선택된 아이"를 추측하면 다자녀 보호자에게 다른 아이 데이터가 섞입니다.

요청한 `childId`가 **이 보호자에게 연결된 아이가 아니면 `403 FORBIDDEN`**으로 막아주세요.

### 5.1 온보딩 — 보호자와 아이 연결 (카카오 로그인 이후)

**초대 코드** 방식입니다(10.12 확정). SMS·전화번호 대조는 비용과 매칭 키 문제로 쓰지 않습니다.
코드는 **본인 확인이 아니라 연결 권한**입니다 — 코드를 가진 사람이 누구인지는 확인하지 않고,
잘못 연결된 사람은 기관(O-19)이나 본인(G-05)이 해제합니다.

보호자는 먼저 회원가입(§2.6)에서 `PARENT`로 가입하고, 그다음 코드로 아이와 연결됩니다.
**역할 결정과는 무관합니다** — 로그인 주소의 `?invite=`로 역할을 정하던 방식은 폐기했습니다.

```
기관: 아이 등록 (O-11) → 코드 발급(10분·일회용) → 카톡 등으로 코드나 링크를 직접 보냄
                                    ↓
보호자: 링크 열기(코드를 sessionStorage에 보관) → 카카오 로그인 → 회원가입(§2.6) → 약관 동의
        → 코드 수락 (G-04) → 공유 범위 동의 (G-02 → G-41)
```

> **연결 완료 ≠ 동의 완료.** 수락(G-04)은 아동 상태를 바꾸지 않습니다. 응답의 `childStatus`는 아동의
> **실제 현재 상태**입니다 — 처음 연결이면 보통 `pending_consent`, 이미 다른 보호자가 동의해 둔 아이면 `active`입니다.

보호자가 **나중에 다른 기관을 추가로 연결**할 때는 기관 코드 입력(G-43 · G-44, §5.5)을
씁니다. 최초 온보딩 경로는 아니고, 설정 화면(P-13)에서만 쓰입니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-02 | GET | `/children/{childId}/consent-preview?institutionId=` | 아이 · 기관 · 공유 범위 미리보기 | P-02 |
| G-04 | POST | `/guardians/me/invitations/accept` | 초대 코드 수락 · **구현됨** | P-01 |
| G-05 | DELETE | `/guardians/me/children/{childId}` | 아이 연결 해제(본인) · **구현됨** | P-11 |

> `G-01 pending-links`(대기 중 연결 목록)와 `G-03 reject`(아이 정보 불일치 반려)는 초대 코드 방식으로 바꾸며
> 결번으로 둡니다. 잘못 연결됐으면 G-05로 끊습니다.

**G-04 요청**

```json
{ "code": "k7p3-qx9m" }
```

공백·하이픈·대소문자는 서버가 맞춥니다. 화면에 보인 `K7P3-QX9M`을 그대로 보내도 됩니다.

**G-04 응답** · `200 OK`

```json
{ "childId": "9", "childName": "김하늘", "organizationId": "1", "organizationName": "○○초등학교",
  "childStatus": "pending_consent" }
```

- `organizationId`: 코드를 발급한 기관 ID(문자열)입니다. 수락 뒤 이 기관에 대한 동의 미리보기(G-02)를 부를 때
  `institutionId`로 그대로 넘기면 됩니다.
- 수락은 연결일 뿐 동의가 아니라 `childStatus`는 바뀌지 않습니다. 동의 미리보기(G-02)·동의 저장(G-41)은
  **아직 BE에 구현되지 않았습니다.**

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `code` 누락·빈 문자열 |
| 400 | `INVALID_INVITE_CODE` | 형식이 틀렸거나, 없거나, 만료·사용·취소된 코드. 아이가 삭제됐거나 발급 기관 명부에서 빠진 경우도 같습니다. 이유를 나누지 않습니다 |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 → 가입부터 |
| 403 | `PARENT_REQUIRED` | 보호자가 아닌 사용자(기관 담당자) |
| 403 | `FORBIDDEN` | CSRF 토큰 누락·불일치 |
| 409 | `LINK_ALREADY_EXISTS` | 이미 연결된 아이. 코드는 쓰이지 않은 채 남습니다 |

**G-05** · `204 No Content` · 보호자 본인이 연결을 끊습니다. 연결되지 않은 아이면 `403 FORBIDDEN`,
기관 담당자가 부르면 `403 PARENT_REQUIRED`입니다. 같은 아이에 다시 연결하려면 기관에서 새 코드를 받습니다.

**G-02 응답** · P-02 화면이 한 번에 그리는 정보입니다.

```json
{
  "child": { "name": "김하늘", "birthDate": "2017-03-14" },
  "institution": { "id": "1", "name": "○○초등학교",
                   "type": "school", "verified": true },
  "documentUrl": "https://…",
  "sharedFields": ["출결 시간", "활동 요약", "알레르기 정보"],
  "notSharedFields": ["사진과 영상", "의료 기록"]
}
```

`sharedFields` / `notSharedFields`는 현재 프론트가 기관 **유형별 상수**로 들고
있습니다. 서버가 실제 동의 범위를 내려주면 상수를 제거하겠습니다.

| 유형 | 공유됨 | 공유 안 됨 |
| --- | --- | --- |
| `school` | 출결 시간, 활동 요약, 알레르기 정보 | 사진과 영상, 의료 기록 |
| `center` | 관찰 요약, 변화 추이, 집에서 해본 방법 | 복지카드 정보 |
| `assistant` | 하원 시간, 이동 경로, 필요한 지원 방법 | 학습 기록 |

### 5.2 아이 · 홈

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-10 | GET | `/guardians/me/children` | 연결된 아이 전체 (다자녀) | 전 화면 |
| G-11 | GET | `/guardians/me/home?childId=` | 홈 데이터 | P-03, P-04 |
| G-12 | GET | `/children/{childId}/today-summary` | TODAY 카드 | P-03, P-07 |
| G-13 | PUT | `/children/{childId}/care-info` | 아이 상세정보 저장 | P-06 |

**G-11 응답** · `{ "child": Child, "activity": ParentActivity[] }`

```json
{ "child": { "…": "Child 모델" },
  "activity": [
    { "id": "1", "at": "2026-08-21 15:20",
      "text": "○○초등학교에 관찰 요약이 전달되었습니다", "journalId": "3" }
  ] }
```

`journalId`가 있으면 프론트가 일지 상세로 연결합니다. 없으면 텍스트만 표시합니다.

**G-12 응답** · 문자열 하나 또는 없으면 `null` (프론트는 카드를 숨깁니다)

**G-13 요청** · `ChildCareInfo` 전체를 그대로 보냅니다 (부분 수정 아님)

```json
{ "welfareCard": true,
  "allergies": ["우유와 유제품", "땅콩"],
  "medications": [{ "name": "○○정", "time": "점심 식후" }],
  "weeklySchedule": [{ "day": "월", "note": "센터 15:00" }] }
```

### 5.3 일지

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-20 | GET | `/children/{childId}/journal` | 일지 목록 (최신순) | P-03, P-07 |
| G-21 | GET | `/journal/{id}` | 일지 상세 | P-08 |
| G-22 | POST | `/journal/{id}/flag` | "이 내용이 이상해요" | P-08 |

**G-20 응답**

```json
[
  { "id": "3", "childId": "1",
    "institution": { "id": "2", "name": "햇살아동발달센터",
                     "type": "center", "verified": true },
    "date": "2026-08-21", "time": "15:20", "isNew": true, "tag": "활동",
    "summary": "오후 미술 활동에 끝까지 참여했어요",
    "detail": "오늘 오후 미술 활동에서는 …",
    "institutionNote": "다음 주 준비물은 …",
    "photoCount": 3, "flagged": false }
]
```

- **정렬은 서버에서 최신순(`date` + `time` 내림차순)으로** 주세요. 프론트도 한 번 더
  정렬하지만 서버 정렬을 기준으로 삼습니다.
- 기관 필터는 현재 프론트에서 처리합니다. 데이터가 많아지면
  `?institutionId=` 쿼리를 추가해주세요.
- `flagged`가 `true`면 프론트는 "확인 요청함"으로 고정 표시하고 버튼을 숨깁니다.

**G-22 응답** · `{ "flagged": true }`. 해당 기관에 재확인 요청이 전달되어야 합니다.

### 5.4 케어 리포트

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-30 | GET | `/children/{childId}/care-report?period=weekly\|monthly` | 리포트 | P-09 |

**응답** · 아직 생성된 리포트가 없으면 **`data`를 `null`**로 주세요 (404 아님).
프론트는 "아직 리포트가 없습니다" 빈 상태를 표시합니다.

```json
{
  "period": "weekly",
  "rangeLabel": "8월 15일 ~ 8월 21일",
  "summary": "이번 주는 오후 활동 참여가 늘었습니다.",
  "trendTitle": "활동 참여 시간",
  "trend": [ { "label": "1주", "value": 3 }, { "label": "2주", "value": 5 } ],
  "trendInsight": "2주 연속 늘고 있습니다.",
  "trendEvidenceIds": ["1", "3"],
  "patterns": [ { "text": "이동 전 예고가 있으면 전환이 쉬웠습니다",
                  "evidenceIds": ["2"] } ],
  "tips": ["집에서도 5분 전 예고를 해보세요"]
}
```

`trendEvidenceIds` · `evidenceIds`는 **G-20에서 조회 가능한 `JournalEntry.id`**여야
합니다. 프론트는 이 id로 근거 일지 화면(P-10)을 그립니다.

### 5.5 동의 · 기관 권한

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-40 | GET | `/children/{childId}/institution-requests/pending` | 대기 중 권한 요청 | P-03, P-04, P-07, P-09, P-11 |
| G-41 | PUT | `/children/{childId}/consent-scopes/{institutionId}` | 동의 부여 / 회수 | P-02, P-04, P-11 |
| G-42 | POST | `/children/{childId}/institution-requests/{institutionId}/decline` | 요청 거절 | P-11 |
| G-43 | GET | `/institutions/lookup?code=` | 기관 코드 조회 | P-13 |
| G-44 | POST | `/children/{childId}/institutions` | 기관 코드로 연결 | P-13 |

**G-40** — 프론트 **알림 배지의 단일 기준점**입니다. 홈 · 타임라인 · 리포트 · 설정
헤더의 빨간 점이 전부 이 엔드포인트 하나를 봅니다.

조건: 해당 아이에 연결됐고 `consent === "not_granted"`이며, 보호자가 **거절하지 않은**
기관.

```json
[ { "institution": { "id": "4", "name": "○○미술학원",
                     "type": "center", "verified": true } } ]
```

**G-41 요청**

```json
{ "action": "grant", "allowedFields": ["daily_summary", "weekly_insight"] }
```

| 필드 | 설명 |
| --- | --- |
| `action` | `grant` \| `revoke` |
| `allowedFields` | 동의 항목. `revoke`에서는 무시됩니다 |

**규칙** · `revoke`는 **앞으로의 전달만** 차단합니다. 이미 보낸 정보는 회수되지
않습니다. 프론트는 이 사실을 회수 확인 시트에 반드시 표시합니다.

**G-42** — 거절은 `consent`를 바꾸지 않습니다(`not_granted` 유지). 기관이 다시 요청할
수 있어야 하므로, **알림 목록에서만 제외**되도록 "이 보호자가 이 기관 요청을 거절함"을
따로 기록해주세요. 프론트는 현재 이 상태를 메모리에만 들고 있어 새로고침하면 사라집니다.

**G-43 응답** · 일치하는 기관이 없으면 `data`를 `null`로 주세요(404 아님).
**조회만 하고 연결하지 않습니다.** 보호자가 공유 범위를 확인한 뒤 G-44로 연결합니다.

**G-44 요청** · `{ "code": "기관 코드" }` → 응답 `{ "institution": Institution }`

### 5.6 기관 요청사항

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-50 | GET | `/children/{childId}/institution-requests` | 준비물 · 확인사항 목록 | P-04, P-12 |
| G-51 | POST | `/institution-requests/{id}/confirm` | 확인 처리 | P-12 |

```json
[ { "id": "1",
    "institution": { "id": "1", "name": "○○초등학교",
                     "type": "school", "verified": true },
    "status": "needs_check",
    "items": ["실내화 준비", "체험학습 동의서 제출"] } ]
```

`status`: `needs_check` · `confirmed`. 확인하면 기관 화면에도 함께 표시되어야 합니다.

---

## 6. 오류 코드

[api-conventions.md](api-conventions.md)의 공통 코드에 더해, 프론트 화면이 분기에
사용하는 도메인 코드입니다. 메시지는 화면에 그대로 노출될 수 있습니다.

### 공통

| 코드 | 상태 | 프론트 동작 |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | 로그인 화면으로 이동 |
| `FORBIDDEN` | 403 | "권한이 없습니다" 표시 |
| `INVALID_REQUEST` | 400 | 입력 오류 표시 |
| `INTERNAL_SERVER_ERROR` | 500 | "잠시 후 다시 시도해주세요" |

### 인증 · 연결 · 동의

| 코드 | 상태 | 화면 문구 |
| --- | --- | --- |
| `KAKAO_AUTH_FAILED` | 401 | "카카오 로그인에 실패했어요. 다시 시도해주세요." (현재 로그인 실패는 JSON이 아니라 `/login?error=login_failed` 리다이렉트로 전달됩니다 — §2.4) |
| `ORGANIZATION_NOT_ASSIGNED` | 403 | 기관 소속이 아닌 회원(보호자)이 기관 전용 API 호출 → "기관 담당자만 이용할 수 있어요" |
| `SIGNUP_NOT_COMPLETED` | 403 | 가입 미완료 회원이 `/auth/me` · `/auth/signup` · `/auth/logout` 외 API 호출 → 역할 선택 화면으로 이동 |
| `ALREADY_SIGNED_UP` | 409 | 이미 가입 완료 → `/auth/me` 재조회 후 역할별 홈으로 이동 (§2.6) |
| `DUPLICATE_BUSINESS_NUMBER` | 409 | "이미 등록된 사업자등록번호입니다" (§2.6) |
| `TERMS_NOT_AGREED` | 403 | 약관 동의 화면으로 이동 |
| `INSTITUTION_CODE_NOT_FOUND` | 404 | "일치하는 기관을 찾지 못했어요. 코드를 다시 확인해주세요." (**G-44 연결 시도에만** 사용. 조회 G-43은 `data: null`로 응답) |
| `PARENT_REQUIRED` | 403 | 보호자가 아닌 회원(기관 담당자)이 보호자 전용 API 호출 → "보호자만 이용할 수 있어요" |
| `INVALID_INVITE_CODE` | 400 | "초대 코드가 올바르지 않거나 만료되었어요." 기관에 새 코드를 요청하도록 안내 (G-04) |
| `LINK_ALREADY_EXISTS` | 409 | "이미 연결된 아이입니다" (G-04) |
| `INVITATION_NOT_FOUND` | 404 | 없거나 이미 쓸 수 없는 초대 코드 (O-17) |
| `GUARDIAN_NOT_FOUND` | 404 | 이 아이와 연결되지 않은 보호자 (O-19) |
| `GUARDIAN_LINKED_BY_OTHER_ORGANIZATION` | 403 | "다른 기관을 통해 연결된 보호자는 해제할 수 없습니다" (O-19) |
| `INVITE_CODE_ISSUE_FAILED` | 503 | "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해주세요." (O-11·O-15) |
| `CONSENT_NOT_GRANTED` | 403 | Gate 2에서 미동의 기관 선택 시 |
| `CHILD_CONSENT_PENDING` | 409 | "보호자 동의가 완료되기 전에는 기록을 올릴 수 없습니다" |

### 기록 · 검토

| 코드 | 상태 | 설명 |
| --- | --- | --- |
| `FILE_TOO_LARGE` | 413 | 업로드 용량 초과 (현재 구현: `RAW_RECORD_FILE_TOO_LARGE`) |
| `UNSUPPORTED_FILE_TYPE` | 400 | 허용되지 않은 확장자 (현재 구현: 입력 오류와 함께 `RAW_RECORD_INVALID_REQUEST`) |
| `CHILD_NOT_FOUND` | 404 | 없는 아이 |
| `RECORD_NOT_FOUND` | 404 | 없는 기록 (현재 구현: `RAW_RECORD_NOT_FOUND`) |
| `MATCHING_RESULT_INVALID_REQUEST` | 400 | 확인 필요 큐 처리 요청 거절 (O-23) |
| `MATCHING_RESULT_NOT_FOUND` | 404 | 없거나 다른 기관의 확인 필요 큐 항목 (O-23) |
| `SUMMARY_INVALID_REQUEST` | 400 | 요약할 기록이 없어 지금 요약 거절 (O-32), 지원하지 않는 `gate1Status` (O-30) |
| `SUMMARY_CHILD_NOT_FOUND` | 404 | 없거나 다른 기관의 아이로 지금 요약 요청 (O-32) |
| `SUMMARY_NOT_FOUND` | 404 | 없거나 다른 기관의 요약 (O-31 · O-33) |
| `SUMMARY_ALREADY_REVIEWED` | 409 | 이미 처리된 요약 재결정 (O-31) |
| `SUMMARY_REGENERATING` | 409 | 새 기록을 반영해 요약을 다시 만드는 중 (O-31) |
| `SUMMARY_NOT_HELD` | 409 | 보류되지 않은 요약 보류 해제 (O-33) |
| `INSIGHT_ALREADY_SENT` | 409 | 이미 발송된 Insight 재발송 |
| `NOT_PRIMARY_SOURCE` | 403 | 근거 제공 기관이 아닌 곳의 Gate 2 승인 시도 |

---

## 7. 구현 우선순위 제안

프론트가 화면별로 바로 붙일 수 있는 순서입니다.

| 순위 | 범위 | 엔드포인트 | 붙는 화면 |
| --- | --- | --- | --- |
| 1 | 인증 · 세션 | `/oauth2/authorization/kakao`(완료), `/auth/logout`(완료), `/auth/me`(완료), `/auth/signup`(완료), `/auth/terms` | I-01, P-01, 전 화면 가드 |
| 2 | 아동 조회 | O-10, O-13, O-14 | I-09, I-09-1 |
| 3 | 기록 등록 · 큐 | O-20 ~ O-25 | I-05, I-06, I-07 |
| 4 | Gate 1 | O-30, O-31, O-33 | I-08, I-03 |
| 5 | 보호자 연결 · 동의 | O-15 ~ O-19, G-04 · G-05(완료), G-02, G-41 | I-04, I-09-1, P-01, P-02 |
| 6 | 보호자 열람 | G-10 ~ G-22 | P-03, P-07, P-08 |
| 7 | Insight · Gate 2 | O-40 ~ O-42 | I-10, I-11 |
| 8 | 동의 관리 · 알림 | G-40, G-42 ~ G-51 | P-04, P-11, P-12, P-13 |
| 9 | 수신함 · 리포트 · 상담 | O-50 ~ O-53, G-30 | I-12, I-13, I-14, P-09 |

각 단계가 끝나면 프론트는 `lib/api.ts`의 해당 함수 본문만 교체하면 되므로,
**엔드포인트 단위로 순차 연동이 가능합니다.** 전부 완성될 때까지 기다릴 필요는
없습니다 (`VITE_USE_MOCK`으로 섞어 쓸 수 있도록 함수별 스위치도 검토 중입니다).
