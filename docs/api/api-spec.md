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
| 2 | **세션 전달 방식** | **확정·구현됨** — JWT 유효시간은 3시간입니다. 로그인 때만 설정하는 `access_token` 원본 쿠키는 현재 로그인 번호를 선택하도록 최대 12시간 보관합니다. **쓰는 동안 자동으로 연장됩니다** — 남은 시간이 절반(1시간 30분)보다 적은 `/api` 요청에는 `access_token_renewal_{sid}` httpOnly 쿠키로 갱신 JWT 를 전달합니다. 두 쿠키는 브라우저가 자동 전송하므로 프론트 변경은 없습니다. 이전 로그인의 늦은 갱신 응답은 새 로그인의 쿠키를 덮지 못합니다. 원본 또는 같은 로그인의 갱신 JWT 가 유효해야 인증되며, 처음 로그인부터 최대 12시간입니다. 로그아웃은 §2.3, 쓰기 요청은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보냅니다. refresh token은 없습니다 | - |
| 3 | **필드 네이밍** | **확정·구현됨** — 프론트↔백엔드 JSON API 는 요청·응답 모두 camelCase입니다(Jackson 기본값). 백엔드↔AI 통신은 별도로 snake_case 입니다 | - |
| 4 | **보호자–아이 연결 방식** | **확정·구현됨 (10.12 변경)** — **초대 코드** 방식입니다. 기관이 아이를 등록하면 10분짜리 일회용 코드가 나오고, 기관이 카톡 등으로 직접 보냅니다. 보호자는 가입한 뒤 코드를 입력해 연결됩니다(§5.1). SMS·전화번호 대조는 쓰지 않습니다. 이전의 "대기 중 연결 요청" 방식은 매칭 키를 정할 수 없어 폐기했습니다. (이것은 **보호자 ↔ 아이** 연결 이야기입니다. 이미 연결된 아이를 **다른 기관**에 붙이는 기관 코드 요청(G-44, O-60~O-62)은 아이와 기관이 이미 정해져 있어 매칭 키 문제가 없습니다) | 링크(`/parent/invite?code=…`)로 보내면 로그인·가입 동안 코드를 sessionStorage에 들고 있다가 가입 뒤 G-04를 부르면 됩니다. 링크가 안 열리는 경우를 위해 코드 입력 칸도 남겨 주세요 |
| 4-1 | **역할 구분** | **확정·구현됨** — 카카오 로그인 뒤 **회원가입에서 사용자가 직접 선택**합니다(§2.6). 기관은 기관명·유형·사업자등록번호를 함께 입력하며, 사업자등록번호는 형식만 검사합니다 | 로그인 직후 `/auth/me`의 `signupCompleted`가 `false`면 역할 선택 화면으로 보냅니다. sessionStorage로 역할을 정하던 방식은 이 흐름으로 바꿔야 합니다 |
| 5 | **파이프라인 진행 상태** | 프론트는 `setTimeout` 시뮬레이션 | 폴링 / SSE / WebSocket 중 선택. 폴링이면 권장 주기와 상태 조회 엔드포인트가 필요합니다 |
| 6 | **페이지네이션** | 미정 | 현재 프론트는 전체 조회를 가정합니다. 큐·타임라인·일지 목록에 커서 또는 오프셋 페이징이 필요하면 형식을 정해주세요 |
| 7 | **파일 업로드** | 현재 구현: `POST /api/v1/raw-records` multipart, **파일 1개**(`file`), 최대 **20MB**, 허용 확장자 `csv` · `txt` · `pdf` · `docx` (§4.3, docx·pdf 텍스트 추출은 #383) — **결정됨**(2026-09-29): jpg/png/hwp는 이번 학기 범위 밖으로 제외 | 다중 업로드는 프론트가 파일마다 한 번씩 보내는 방식으로 처리 중 |
| 8 | **RawRecord 응답 형식** | 현재 구현 응답이 프론트 `RawRecord` 타입과 다릅니다 (§3 RawRecord의 "현재 구현" 참고) | 프론트 `RawRecord` 타입(§3.2)과 매핑표 합의 필요 |

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
  "data": { "userId": "5", "name": "박지현", "signupCompleted": false, "termsAgreed": false }
}
```

**가입 완료 — 기관**

```json
{
  "result": "SUCCESS",
  "data": { "role": "org", "userId": "5", "name": "박지현", "institutionId": "3", "institutionName": "햇살아동발달센터", "signupCompleted": true, "termsAgreed": true }
}
```

**가입 완료 — 보호자**

```json
{
  "result": "SUCCESS",
  "data": { "role": "parent", "userId": "5", "name": "박지현", "signupCompleted": true, "termsAgreed": true }
}
```

| 필드 | 타입 | 항상 포함 | 설명 |
| --- | --- | --- | --- |
| `signupCompleted` | boolean | 예 | `false`면 가입 미완료. 프론트는 역할 선택 화면으로 보냅니다 |
| `userId` | string | 예 | 내부 PK를 문자열로 직렬화한 값입니다 (`"u_1"` 같은 접두사는 붙지 않습니다) |
| `name` | string | 아니오 | 카카오 닉네임. 동의를 거부하면 **이 필드가 빠집니다** |
| `role` | `"org"` \| `"parent"` | 아니오 | `signupCompleted: true`일 때만. `admin`은 아직 없습니다 |
| `institutionId` | string | 아니오 | `role === "org"`일 때만 |
| `institutionName` | string | 아니오 | `role === "org"`일 때만. 가입 때 입력한 기관명입니다 (#236) |
| `termsAgreed` | boolean | 예 | 자기 역할의 서비스 약관(통과로 치는 판)에 동의했는지 (§2.5). `false`면 프론트는 보호자를 약관 단계(`/parent/invite`)로, 기관을 기관 약관 화면(`/org-terms`)으로 보냅니다. 가입 미완료는 `false` |

**프론트는 가입 여부를 `signupCompleted`만 보고 분기합니다.** `role`이 없다고 로그인 화면으로 보내면
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

**그 로그인의 출입증을 모두 무효로 합니다.** 출입증에는 로그인마다 다른 번호가 들어 있고(연장해도 그대로), 로그아웃하면
서버가 그 번호를 기록해(DB 스키마 §4.4) 이후 그 번호의 출입증은 연장된 것까지 인증하지 않습니다(`401`). 로그아웃 직전에
나간 요청의 응답이 늦게 도착해 연장 쿠키를 다시 심어도, 다른 탭이어도, 출입증이 새어 나갔어도 쓸 수 없습니다.
로그아웃 요청의 출입증이 처리 전에 만료됐어도, 서명이 유효하고 최대 로그인 시간(처음 로그인부터 12시간)이 남아 있으면
같은 로그인 번호를 무효로 합니다. 최대 로그인 시간이 지났으면 쿠키만 지웁니다.
같은 회원의 **다른 로그인**(다른 기기 · 같은 기관 계정을 쓰는 다른 선생님)은 그대로입니다. 로그아웃 응답에는 연장 쿠키가
실리지 않습니다. 기록에 실패해도(DB 장애) 쿠키는 지우고 `204` 입니다.
원본 쿠키가 없어지면 남아 있거나 늦게 도착한 갱신 쿠키만으로는 인증되지 않습니다. 다시 로그인하면 새 원본이 선택하는
로그인 번호만 사용하므로, 이전 로그인의 갱신 응답이 늦게 와도 새 로그인을 끊거나 이전 계정으로 되돌리지 않습니다.

> **예외 — 로그인 자동 연장 전에 발급된 출입증.** 로그인 번호가 없어 로그아웃해도 서버가 무효로 하지 못합니다. 쿠키는
> 지워지지만 복사본은 원래 만료(발급 뒤 3시간)까지 유효합니다. 대신 연장되지 않아 그 뒤로는 쓸 수 없습니다. 무효화는
> 자동 연장 배포 뒤에 로그인한 출입증부터 적용됩니다.

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
[§2.2 `GET /api/v1/auth/me`](#22-세션-조회--라우트-가드가-매-진입마다-호출)의 `signupCompleted` · `termsAgreed`로 확인합니다.

#### 2.4.1 개발용 로그인 — 개발·테스트 전용

카카오 계정 없이 여러 회원으로 가입·초대·동의 흐름을 테스트할 때 씁니다. 백엔드를
`DEV_LOGIN_ENABLED=true`로 실행했을 때만 존재하고, 꺼져 있으면 `404`입니다. 로컬 기본값은 꺼짐이고,
배포 서버는 테스트용으로 Compose가 켜 둡니다(`infra/docker/compose.yaml`). 정식 오픈 전에는 끕니다.

```http
GET /api/v1/dev/login?as=parent1       ← 브라우저 주소창으로 연다 (fetch 아님)
```

| 결과 | 동작 |
| --- | --- |
| 성공 | `dev:{as}`를 카카오 회원번호처럼 써서 회원을 찾거나 만들고(닉네임은 `as`), `access_token` 쿠키를 심은 뒤 `302`로 `AUTH_SUCCESS_REDIRECT`(기본 `/oauth/success`)로 보냄. 바디 없음 |
| `as` 누락·형식 오류 | `400 INVALID_REQUEST`. `as`는 영문 소문자·숫자·하이픈 1~20자 |

그 뒤는 카카오 로그인과 같습니다. 처음 쓰는 이름은 가입 미완료 회원이라 [§2.6 회원가입](#26-회원가입)을
거칩니다. 다른 회원으로 바꾸려면 `as`만 바꿔 다시 열면 됩니다. GET이라 CSRF 토큰 검사를 받지 않습니다.
기존 쿠키를 든 채로 열어도 그 쿠키는 연장하지 않습니다 — 응답의 `access_token` 쿠키는 새 로그인 쿠키 하나뿐입니다.

### 2.5 서비스 약관 동의

> **구현 완료 (보호자 #337 · 기관 #354).** 보호자(P-01)와 기관 담당자(I-16)가 씁니다. 카카오 동의 항목과 별개로 서버가 동의 기록을 남깁니다(DB 스키마 §4.3).

```http
POST /api/v1/auth/terms   → 200 OK
{ "agreed": ["tos", "privacy"],
  "versions": { "tos": "2026-11-07", "privacy": "2026-11-07" } }               보호자
{ "agreed": ["org_tos", "org_privacy", "org_child_data"],
  "versions": { "org_tos": "2026-11-07", "org_privacy": "2026-11-07", "org_child_data": "2026-11-07" } }   기관 담당자
```

출입증 쿠키와 `X-XSRF-TOKEN` 헤더가 필요합니다.

`versions` 는 동의 화면이 [약관 본문](#251-약관-본문)에서 받아 둔 판입니다 — 사용자가 읽은 판으로 기록하려는 것입니다.
키는 `agreed` 와 같아야 합니다. **선택이지만 곧 필수가 됩니다**(아래 처리 규칙 4).

| 키 | 라벨 | 역할 | 필수 |
| --- | --- | --- | --- |
| `tos` | 서비스 이용약관 동의 | 보호자 | 예 |
| `privacy` | 개인정보와 민감정보 수집 동의 | 보호자 | 예 |
| `org_tos` | 기관 서비스 이용약관 동의 | 기관 담당자 | 예 |
| `org_privacy` | 담당자 개인정보 수집 동의 | 기관 담당자 | 예 |
| `org_child_data` | 아동 정보 보호 서약 | 기관 담당자 | 예 |

자기 역할의 약관만 보냅니다. 다른 역할의 약관이 섞이면 `400`입니다.

예전 초안의 선택 항목 `notify`(새로운 소식 알림 받기)는 뺐습니다 — 알림을 보내는 기능이 없습니다. 보내면 모르는 키라 `400`입니다.

**응답**

```json
{ "result": "SUCCESS", "data": { "termsAgreed": true } }
```

**처리 규칙**

1. 어느 판으로 기록하나 — `versions` 를 보내면 판이 모두 **지금 판**이어야 하고 그 판으로 기록합니다. 하나라도 다르면
   화면을 연 뒤 약관이 바뀐 것이라 아무것도 기록하지 않고 `409 TERMS_VERSION_CHANGED` 입니다. `versions` 가 없으면 본문을
   보여 주지 못한 옛 화면으로 보고 **본문이 없던 판**(보호자 `2026-11-03`, 기관 `2026-11-05`)으로 기록합니다
2. 기록할 판에 이미 동의했으면 아무것도 바꾸지 않고 같은 응답입니다(멱등). 같은 회원의 요청이 겹쳐도 회원 행을 잠가 하나씩 처리하므로 둘 다 `200`입니다.
3. 기록은 쌓기만 합니다. 판이 바뀐 뒤 다시 동의하면 새 판 기록이 더해지고 옛 기록은 남습니다. 철회 API 는 없습니다(필수 약관 철회는 회원 탈퇴).
4. **한 판 = 한 본문.** 내보낸 판의 글은 고치지 않고, 글을 바꾸면(초안 수정 포함) 새 판입니다. 본문 판과 "다시 동의를 요구하는가"는 따로 정합니다 —
   개인정보 수집(`privacy` · `org_privacy`)과 아동 정보 보호 서약(`org_child_data`)은 **통과로 치는 판**에 동의해야 통과합니다.
   지금 통과 판은 본문이 없던 판과 지금 판(`2026-11-07`) 둘이라, 이미 동의한 사람은 다시 동의하지 않습니다. 초안을 확정할 때는
   `versions` 를 필수로 바꾼 뒤 통과 판을 확정 판 하나로 줄여 다시 받습니다.
   이용약관(`tos` · `org_tos`)은 한 번 동의했으면 다시 받지 않습니다(바뀐 내용은 공지로 알립니다 — 공지 기능은 아직 없습니다).

| 상태 | 코드 | 조건 | 프론트 동작 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | `agreed` 누락 · 빈 목록 · `null` 원소, 모르는 키, 다른 역할의 약관이 섞임, 필수 약관 누락, `versions` 의 키가 `agreed` 와 다름 · `null` 판 | "동의를 저장하지 못했어요" 표시 |
| 401 | `UNAUTHORIZED` · `SESSION_USER_NOT_FOUND` | 비로그인 · 없는 회원 | 로그인 화면으로 |
| 403 | `SIGNUP_NOT_COMPLETED` | 가입 미완료 | 가입 화면으로 |
| 403 | `FORBIDDEN` | CSRF 토큰 누락 또는 불일치 | |
| 409 | `TERMS_VERSION_CHANGED` | `versions` 의 판이 지금 판과 다름 — 화면을 연 뒤 약관이 바뀌었다. 아무것도 기록하지 않음 | 약관을 다시 받아 바뀐 약관의 체크를 풀고 다시 확인하게 |

**차단** — 동의하지 않은 회원(`/auth/me` 의 `termsAgreed: false`, 보호자 · 기관 담당자 모두)은 `/auth/me` · `/auth/terms` · `/auth/logout` · 약관 본문 · 개발용 로그인 · 헬스 체크 외의
API 가 모두 `403 TERMS_NOT_AGREED`입니다. 가입 미완료 차단과 같은 필터가 막고, **가입 미완료를 먼저 봅니다.**
프론트는 공통 요청 함수에서 이 오류를 받으면 세션을 다시 읽어 기관은 `/org-terms`, 보호자는 약관 단계(`/parent/invite`), 로그인이 풀렸으면 로그인 화면으로 보냅니다. 세션 조회가 실패하면 옮기지 않고 그 화면의 오류 처리에 맡깁니다.

#### 2.5.1 약관 본문

```http
GET /api/v1/terms?role=parent   → 200 OK      (role: parent | org)
```

그 역할이 동의해야 하는 약관을 지금 판의 본문까지 모두 줍니다. **로그인 없이 부릅니다** — 약관은 공개 문서이고 동의하기 전에
읽어야 하는 글이라, 가입 미완료 · 약관 미동의 차단에서도 빠집니다. 정확히 이 경로만 열려 있습니다.
`Cache-Control: no-cache` 입니다.

```json
{ "result": "SUCCESS", "data": [
  { "key": "tos", "title": "서비스 이용약관", "version": "2026-11-07", "effectiveDate": "2026-11-07",
    "draft": true, "body": "제1조(목적)\n이 약관은 …" },
  { "key": "privacy", "title": "개인정보와 민감정보 수집 · 이용", "version": "2026-11-07", … }
] }
```

| 필드 | 설명 |
| --- | --- |
| `key` | 약관 키. 동의 요청의 `agreed` · `versions` 키와 같다. 보호자 `tos` · `privacy`, 기관 `org_tos` · `org_privacy` · `org_child_data` 순서 |
| `title` | 화면 제목 |
| `version` | 지금 판. 동의할 때 `versions` 에 그대로 보낸다 |
| `effectiveDate` | 시행일 (YYYY-MM-DD) |
| `draft` | 팀 검토 전 초안이면 `true` — 화면은 본문 위에 초안 표시를 띄운다 |
| `body` | 본문. 줄바꿈만 살리는 그냥 글이다 — 조항 번호와 `- ` 목록은 글자 그대로 |

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `role` 누락 또는 `parent` · `org` 가 아님 |

> **지금 문안은 초안입니다** (`draft: true`). 보유 기간 · AI 처리 업체와 위치 · 문의처는 자리 표시로 남아 있습니다.
> 확정할 때 민감정보(보호자의 알레르기 · 복용약)를 일반 개인정보와 따로 동의받을지도 함께 정합니다.

> **아직 없는 것** — 약관이 바뀌었을 때 앱 안 공지.

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

**응답 — `201 Created`.** 본문은 §2.2의 가입 완료 응답과 같습니다. 막 가입한 회원은 보호자 · 기관 모두 동의 기록이 없어
`termsAgreed: false`입니다(프론트가 그 역할의 약관 화면으로 갑니다).

```json
{
  "result": "SUCCESS",
  "data": { "role": "org", "userId": "5", "name": "박지현", "institutionId": "3", "institutionName": "햇살아동발달센터", "signupCompleted": true, "termsAgreed": false }
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
    "allergies": ["우유와 유제품"],
    "medications": [{ "name": "○○정", "time": "점심 식후" }]
  }
}
```

`status`: `pending_consent` · `active` · `suspended`
`consent`: `granted` · `not_granted` · `revoked`

> **현재 구현** (보호자 아이 목록 G-10 · 홈 G-11) · `school`은 **키째 내려가지 않습니다.** 저장하는 곳이
> 없어서입니다. 프론트 타입에서도 선택 필드라(`school?`) 화면은 그 부분을 생략합니다. 학교 이름은 `institutions`에 있습니다.
> `care`(돌봄 정보, G-13)는 **늘 실립니다.** 저장한 적이 없으면 `{ "allergies": [], "medications": [] }`입니다
> (`null` 은 프론트 타입이 허용하지 않음). 보호자 응답에만 실리고 기관 쪽 응답에는 없습니다.
> 예전 초안의 `welfareCard`(복지카드)·`weeklySchedule`(주간 스케줄)은 뺐습니다(#285) — G-13 참고.
> `institution.verified`는 언제나 `false`입니다(G-02와 같음).

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
| O-01 | GET | `/institutions/me` | 내 기관 정보 · **구현됨** | I-02, I-09 |
| O-02 | PATCH | `/institutions/me` | 기관명 · 유형 수정 | I-02 |
| O-03 | POST | `/institutions/me/documents` | 증빙서류 업로드 (multipart) | I-02 |

**O-01 응답** · `Institution`(§3)에 기관 코드를 더했습니다.

```json
{ "id": "2", "name": "햇살아동발달센터", "type": "center", "verified": false, "code": "K7P3QX9M" }
```

- `code`: **기관 코드.** 기관마다 고정인 8자(대문자·숫자, `0 O 1 I L` 제외)입니다. 화면에는 `K7P3-QX9M`처럼 4-4로 보여 주세요.
  보호자에게 알려 주면, 보호자가 이 코드로 이미 다른 기관에 다니는 아이를 이 기관에 연결해 달라고 요청합니다(G-44 → O-60).
  바뀌지 않습니다(재발급 없음).
- 이 기능 전에 가입한 기관은 **처음 열 때** 코드가 만들어집니다. 그래서 GET 이지만 쓰기가 일어날 수 있습니다.

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
| 403 | `ORGANIZATION_NOT_ASSIGNED` | 기관 소속이 아닌 회원(보호자) |
| 503 | `INSTITUTION_CODE_ISSUE_FAILED` | 동시에 만든 코드와 겹침. 다시 요청 |

**O-03 요청** · `multipart/form-data`, 필드 `file`
**O-03 응답** · `{ "fileName": "인가증_2026.pdf", "status": "reviewing" }`
(`status`: `verified` · `reviewing` · `rejected`)

### 4.2 아동

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-10 | GET | `/institutions/me/children` | 담당 아동 목록 · **구현됨** | I-05, I-09, I-13 |
| O-11 | POST | `/institutions/me/children` | 아이 등록 + 초대 코드 발급 · **구현됨** | I-04 |
| ~~O-13~~ | ~~GET~~ | ~~`/children/{childId}`~~ | 아동 상세 · **만들지 않음** (#297, 아래) | — |
| O-14 | GET | `/institutions/me/children/{childId}/timeline` | 기록 타임라인 — 우리 기관이 승인한 요약 · **구현됨** | I-09-1 |
| O-15 | POST | `/institutions/me/children/{childId}/invitations` | 초대 코드 추가 발급 · **구현됨** | I-04, I-09-1 |
| O-16 | GET | `/institutions/me/children/{childId}/invitations` | 쓸 수 있는 초대 코드 목록 · **구현됨** | I-04, I-09-1 |
| O-17 | DELETE | `/institutions/me/children/{childId}/invitations/{invitationId}` | 초대 코드 취소 · **구현됨** | I-04, I-09-1 |
| O-18 | GET | `/institutions/me/children/{childId}/guardians` | 연결된 보호자 목록 · **구현됨** | I-09-1 |
| O-19 | DELETE | `/institutions/me/children/{childId}/guardians/{guardianUserId}` | 보호자 연결 해제 · **구현됨** | I-09-1 |
| O-60 | GET | `/institutions/me/link-requests` | 보호자의 기관 연결 요청 목록 · **구현됨** | I-09 |
| O-61 | POST | `/institutions/me/link-requests/{childId}/accept` | 연결 요청 수락 · **구현됨** | I-09 |
| O-62 | POST | `/institutions/me/link-requests/{childId}/reject` | 연결 요청 거절 · **구현됨** | I-09 |

> 이전 초안에 있던 `O-12 초대코드 재발급`은 결번으로 둡니다. 재발급은 "취소(O-17) 후 새로 발급(O-15)"입니다.

**O-10 응답** · RosterPicker(매칭 확인 · Gate 1 아동 변경)가 쓰는 필드와 기관 관리번호만 우선 구현했습니다.

```json
[
  { "id": "9", "name": "김하늘", "birthDate": "2017-03-14", "externalId": "2026-0031",
    "status": "active", "consent": "granted" },
  { "id": "10", "name": "임유진", "birthDate": "2019-11-26", "externalId": null,
    "status": "active", "consent": "not_granted" }
]
```

`externalId`는 이 기관이 붙인 관리번호입니다(O-11). 등록할 때 필수지만, 필수가 되기 전에 등록된 아이는 번호가 없어 `null`입니다.

**기관 화면은 `status`가 아니라 `consent`로 판단해 주세요** (#258).

- `status`는 아이 전체 요약입니다. 어느 기관이든 동의가 있으면 `active`입니다.
- `consent`는 **이 기관에 대한** 동의 상태(`granted` · `not_granted` · `revoked`)입니다.
- 위 예의 임유진처럼 학교에 동의한 아이가 센터 명부에 막 올라오면 `status`는 `active`인데 센터의 `consent`는 `not_granted`입니다.
  `status`로 거르면 센터가 아직 매칭할 수 없는 아이를 "이용 중"·매칭 후보로 보여 줍니다.

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
- **같은 기관 명부에 이름·생년월일이 같은 아이가 있으면 409 `DUPLICATE_CHILD`입니다.** 관리번호만 다르게 넣어 같은 아이가
  두 번 들어가는 것을 막습니다. 명부에서 뺀 아이는 보지 않습니다. 다른 기관이 같은 아이를 등록하면 별도 아동(`id`)이 됩니다.
  **이미 다른 기관에 다니는 아이는 새로 등록하지 말고**, 보호자에게 기관 코드(O-01)를 알려 줘 연결 요청(G-44)을 받아 수락(O-61)해 주세요.
  그래야 아이가 하나로 남아 기관들의 기록이 한 아이에게 모입니다.

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
| 409 | `DUPLICATE_CHILD` | 같은 기관 명부에 이름·생년월일이 같은 아이가 이미 있음 |
| 503 | `INVITE_CODE_ISSUE_FAILED` | 동시에 발급된 코드와 겹침(거의 일어나지 않음). 아이도 등록되지 않았으니 다시 요청 |

등록 직후 아이는 `pending_consent` 상태입니다. 보호자가 초대 코드로 연결(G-04)하고 **이 기관에** 공유 동의(G-41)를
해야 이 기관의 AI 매칭 명단에 들어갑니다. **연결만으로는 `active`가 되지 않습니다** — 동의는 별도 단계입니다.

> **동의는 기관별입니다 (#184).** `status`는 "어느 기관이든 동의가 있으면 `active`, 모두 철회됐으면 `suspended`"인
> 요약입니다. 아이가 학교에만 동의했으면 우리 기관 명부에도 `active`로 보이지만, 우리 기관 매칭 명단에는 들어가지 않습니다.

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
| 403 | `GUARDIAN_LINKED_BY_OTHER_ORGANIZATION` | (O-19) 우리 기관 코드로 연결하지 않은 보호자 |
| 404 | `CHILD_NOT_FOUND` | 없거나 삭제됐거나 **우리 기관 명부에 없는** 아이. 다른 기관 아이도 같은 404입니다 |
| 404 | `INVITATION_NOT_FOUND` | (O-17) 없거나 이미 쓸 수 없는 코드 |
| 404 | `GUARDIAN_NOT_FOUND` | (O-19) 이 아이와 연결되지 않은 보호자 |
| 503 | `INVITE_CODE_ISSUE_FAILED` | (O-15) 동시에 발급된 코드와 겹침. 다시 요청 |

**O-14 응답** · 우리 기관이 1차 검토(Gate 1)에서 승인한 요약입니다(#292). **관찰 날짜 최근 순**, 같은 날은 승인 최근 순입니다.

```json
[
  { "id": "12", "date": "2026-08-21", "content": "오후 자유놀이 종료 안내 후 스스로 정리함.",
    "edited": false, "sourceCount": 1 },
  { "id": "9", "date": "2026-08-21", "content": "11:40 급식실 입장 직후 소리를 지름. 손을 잡고 30까지 세자 진정됨.",
    "edited": true, "sourceCount": 2 }
]
```

| 필드 | 뜻 |
| --- | --- |
| `id` | 승인본(`child_context`) id |
| `date` | 관찰 날짜 |
| `content` | 담당자가 승인한 최종본. AI 원문이 아닙니다 — 고쳐서 승인하면 최종본은 승인본에만 있습니다 |
| `edited` | 1차 검토에서 고쳐서 승인했는지 ("선생님 수정") |
| `sourceCount` | 그 요약에 묶인 원본 일지 수. 저장된 목록을 읽을 수 없으면 0 |

- **우리 기관 기록만입니다.** 다른 기관 기록은 나오지 않고, 다른 기관이 이 아이를 기록 중이라는 사실도 알리지 않습니다.
  기관 사이에는 2차 검토를 지난 인사이트(수신함 O-50 · O-51)로만 넘어갑니다.
- **기록이 없는 날은 항목이 없습니다.** 추정치로 채우지 않습니다. 예전 초안의 "빈 날도 `entry: null` 로" 와 기록 종류 ·
  검증 결과 · 버전 칸은 뺐습니다.
- **같은 날 승인본이 여럿이면 따로 나옵니다.** 승인 뒤 같은 날 일지가 더 오면 새 일지만 묶은 요약이 따로 승인됩니다.
- **보호자가 우리 기관 동의를 꺼도 지난 기록은 나옵니다.** 동의 철회는 다른 기관으로 넘어가는 것과 새 처리를 막습니다.
- 한 번에 전부 줍니다.

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
| 403 | `ORGANIZATION_NOT_ASSIGNED` | 기관 소속이 아닌 사용자(보호자) |
| 404 | `CHILD_NOT_FOUND` | 없거나 삭제됐거나 **우리 기관 명부에 없는** 아이. 다른 기관 아이도 같은 404입니다 |

**O-13(아동 상세)은 만들지 않습니다** (#297). 초안은 아이 정보와 "이 아이와 연결된 기관 · 기관별 동의 상태"를 주려 했습니다.

- 아이 정보(이름 · 생년월일 · 관리번호 · 우리 기관 동의)는 명부(O-10)에 이미 있습니다. 화면은 명부에서 아이를 찾습니다.
- **다른 기관 정보는 기관에 주지 않습니다.** "언어치료센터에 다닌다" 같은 사실은 건강 관련 민감정보일 수 있고, 보호자는
  기관마다 따로 동의할 뿐 다른 기관에 알리는 데 동의하지 않았습니다. 기관 타임라인(O-14)을 우리 기관 기록만으로 정한 것과
  같은 원칙입니다.
- 예외는 Gate 2 목록(O-40)의 `targets`(받을 후보 기관과 동의 상태)입니다. 승인하면 어디로 가는지 알고 눌러야 해서 그 글의
  승인 주체에게만 보입니다.

**O-60 응답** · 보호자가 기관 코드로 보낸, 기다리는 연결 요청입니다. 같은 아이에 보호자 여럿이 요청했으면 한 항목으로 묶습니다.
가장 먼저 요청한 아이부터입니다. 없으면 `[]`.

```json
[ { "child": { "id": "10", "name": "임유진", "birthDate": "2019-11-26" },
    "requesters": [ { "name": "엄마" }, { "name": "아빠" } ],
    "requestedAt": "2026-10-29T10:00:00+09:00" } ]
```

- `requesters[].name`: 카카오 닉네임입니다. 제공에 동의하지 않은 보호자는 `null`입니다.
- `requestedAt`: 가장 먼저 요청한 시각입니다.

**O-61 요청** · `{ "externalId": "2026-0042" }` — 아이 등록(O-11)과 같은 규칙입니다(필수, 앞뒤 공백 제거, 1–50자, 같은 기관 안 중복 409).

**O-61 응답** · 명부에 올라간 아이 한 줄(O-10 항목과 같은 모양). 수락은 동의가 아니라서 이 기관의 `consent`는 `not_granted`입니다.
보호자에게는 "새 기관 권한 요청"(G-40)으로 뜨고, 보호자가 동의(G-41)해야 매칭 대상이 됩니다.
이 아이에 대한 기다리는 요청은 보호자가 여럿이어도 함께 수락됩니다. 예전에 명부에서 빠졌던 아이면 그 연결을 새 관리번호로 되살립니다.

**O-62** · `204 No Content`. 이 아이에 대한 기다리는 요청을 모두 거절합니다. 보호자에게 따로 알리지 않고, 보호자는 다시 요청할 수 있습니다.

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | (O-61) `externalId` 누락·공백·50자 초과 |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` · `ORGANIZATION_NOT_ASSIGNED` · `FORBIDDEN` | 가입 미완료 · 기관 소속 아님 · CSRF 토큰 누락·불일치 |
| 404 | `LINK_REQUEST_NOT_FOUND` | (O-61·O-62) 기다리는 요청이 없는 아이 — 삭제된 아이, 다른 기관에 낸 요청, 이미 처리된 요청, 요청한 보호자의 연결 해제로 거둬진 요청 |
| 409 | `INSTITUTION_ALREADY_LINKED` | (O-61) 이미 명부에 있는 아이 |
| 409 | `DUPLICATE_EXTERNAL_ID` | (O-61) 이미 쓰고 있는 관리번호. 요청은 그대로 남습니다 |

### 4.3 기록 등록 · 큐

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-20 | POST | `/raw-records` | 원본 기록 업로드 (multipart) | I-05 |
| O-21 | GET | `/raw-records/{id}/status` | 파이프라인 진행 상태 | I-03, I-05 |
| O-22 | GET | `/matching-queue` | 확인 필요 큐 · **구현됨** | I-03, I-06 |
| O-23 | POST | `/matching-queue/{itemId}/resolve` | 아이 확정 / 제외 · **구현됨** | I-06 |
| O-24 | GET | `/validation-results?status=BLOCK` | 수정 요청 큐 · **구현됨** | I-03, I-07 |
| O-25 | POST | `/validation-results/{itemId}/resolve` · `/validation-results/{itemId}/revise` | 보류 / 고쳐서 다시 검사 · **구현됨** (#357) | I-07 |
| O-26 | GET | `/raw-records/progress` | 파일별 처리 현황 · **구현됨** | I-03, I-05 |
| O-27 | POST | `/raw-records/{id}/retry` | 실패한 기록 재처리 · **만들지 않음** (#181) | I-03, I-05 |
| O-28 | GET | `/matching-queue/{itemId}` | 확인 필요 큐 항목 하나 · **구현됨** (#331) | I-06 |

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
> | 허용 확장자 | `csv` · `txt` · `pdf` · `docx` (`docx`는 #383에서 추가. 2026-09-29, 이슈 #67로 `jpg`·`jpeg`·`png`·`hwp` 제외 — jpg/png는 OCR 필요, hwp는 자바 파싱이 매우 어려워 이번 학기 범위 밖) |
> | 허용 Content-Type | `text/csv` · `application/vnd.ms-excel`(Windows가 csv에 흔히 붙이는 값, #113) · `text/plain` · `application/pdf` · `application/vnd.openxmlformats-officedocument.wordprocessingml.document`(docx) |
> | 기록 분리(텍스트 추출) | 네 형식 모두 지원(#383). `docx`는 문단과 표 칸을 문서 순서대로 한 줄씩, `pdf`는 글자 위치 순서로 뽑고, 표 칸 끝에서 꺾인 줄은 다음 줄이 날짜로 시작하지 않으면 이어 붙입니다(#385). `docx`·`pdf`는 **첫 날짜 앞 머리말(제목·아동 이름·생년월일)을 기록으로 만들지 않습니다**(날짜가 하나도 없으면 전체를 한 건으로 둡니다). 텍스트를 못 뽑는 파일(깨진 docx, 암호 걸린 pdf, 사진만 있는 pdf)은 업로드는 성공하고 `entries: []`로 남습니다. `docx`는 압축 안에서 XML로 읽힐 수 있는 파일의 합이 5MB, 전체가 30MB를 넘게 풀리면 같은 "못 읽은 파일"입니다(#424) |
> | 최대 크기 | 20MB (Caddy는 25MiB에서 먼저 차단) |
> | 기록 상한 (#424) | 파일 하나에서 나온 기록 **500건**(`app.raw-record.limits.max-entries-per-file`), 기록 하나 **5,000자**까지. 넘으면 파일을 저장하지 않고 거절합니다 — 나눠 올려 주세요 |
> | AI 처리 줄 상한 (#424) | 그 기관에서 AI 처리를 기다리거나 처리 중인 기록(`PENDING · MATCHING · MATCHED · VALIDATING · VALIDATED · SUMMARIZING`)과 이 파일의 기록을 합쳐 **1,000건**(`app.agent-queue.max-awaiting-entries`)까지. 아래 "AI 처리 줄 상한" 참고 |
> | 동시 처리 (#424) | 글자 뽑기 · 기록 나누기를 서버 전체에서 동시에 2건까지 하고, 4건까지 최대 10초 기다립니다. 화면은 파일을 2개씩 차례로 보냅니다 |
> | 성공 | `201 Created` |
> | 오류 | 확장자·형식·파일명 오류 `400 RAW_RECORD_INVALID_REQUEST`, 기록이 너무 많거나 긴 파일 `400 RAW_RECORD_ENTRY_LIMIT_EXCEEDED`, 용량 초과 `413 RAW_RECORD_FILE_TOO_LARGE`, AI 처리 줄이 꽉 참 `429 AGENT_QUEUE_FULL`, 동시 업로드가 많음 `429 RAW_RECORD_UPLOAD_BUSY`, 저장 실패 `500 RAW_RECORD_STORAGE_FAILED`. 두 400 과 두 429 는 화면이 `code` 로 가릅니다. 기록 저장이 실패해도 업로드 전체가 `500` 입니다(원본 행과 기록을 한 번에 저장) |
>
> **AI 처리 줄 상한이 지키는 범위 (#424)** — **사람이 일으키는 새 유입을 막는 상한**입니다. 업로드와, 사람 확인에서 AI 처리로
> 다시 들어가는 네 경로(O-23 아이 고르기 · O-25 본문 고치기 · O-36 다시 요약 · O-31 `revert`)가 들어오기 직전에 기관 행을 잠그고
> 셉니다. 동시에 들어와도 함께 통과하지 않습니다. 요약 워커가 새 기록이 든 묶음을 다시 요약하며 그 묶음의 Gate 1 대기 기록을 함께
> 담는 것은 막지 않습니다 — 막으면 줄이 꽉 찼을 때 워커가 묶음을 영영 집지 못합니다. 그래서 줄은 그 기관의 Gate 1 대기 기록 수만큼
> 잠깐 상한을 넘을 수 있습니다. 하루 · 한 달 누적 AI 비용이나 기관 사이 처리 순서는 이 상한이 지키지 않습니다.
>
> **AI 에 보내는 기록 길이 (#424)** — 상한 전에 올라온 5,000자 넘는 기록은 매칭 · 검증 · 요약 요청을 만들기 전에 멈춥니다. 잘라
> 보내지 않고 `SYSTEM_ERROR`(원인 `CONTENT_TOO_LONG`, DB 스키마 §6.2)로 남아 처리 현황(O-26)에 실패로 보입니다. 줄인 파일을 다시
> 올려 정리합니다.
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
    "hintName": null,
    "record": { "id": "3", "fileName": "0821_활동일지.docx",
                "preview": "…", "capturedAt": "2026-08-21", "fullContent": "…" },
    "candidates": [ { "childId": "1", "name": "김하늘", "birthDate": "2020-01-01", "confidence": 0.62 } ],
    "evidence": [ { "start": 12, "end": 15 } ] }
]
```

- `id`·`record.id`·`candidates[].childId`·`matchedChildId`는 문자열, `journalEntryId`는 숫자입니다 (api-conventions.md의 PK 규칙 참고)
- `journalEntryId`: 이 결과가 가리키는 기록(`journal_entry`)의 ID
- `matchedChildId`: 확정된 아동 ID. 큐에 남아 있는 항목은 아직 확정 전이라 보통 `null`입니다
- `hintName`: 표지 힌트 — 업로드 때 **파일 이름**에 명부 아이 이름이 정확히 한 명 들어 있으면 그 이름, 아니면 `null` (#381). 일지 본문에서 찾은 이름은 `evidence` 입니다. 아이 확인 화면은 `hintName` 이 있으면 "표지 이름 '○○'", 없으면 파일 이름을 보입니다
- 값이 없는 필드(`matchedChildId`·`multiReason`·`evidence` 등)는 생략되지 않고 `null`로 내려갑니다
- `candidates`에는 **우리 기관 명부(연결 해제·삭제 제외)에 있는 아이만** 나옵니다. AI 가 명부 밖 번호를 줘도 그 후보는 빠집니다 (#331)

**O-28 응답** · `GET /matching-queue/{itemId}` — O-22 항목 하나와 같은 모양입니다 (#331).
선생님이 한 건을 열 때 이 API 로 본문(`record.fullContent`)·후보·근거를 받아 주세요.
우리 기관의 **처리 대기** 항목이 아니면 `404 MATCHING_RESULT_NOT_FOUND`입니다 — 없는 항목, 다른 기관 항목,
이미 처리된 항목을 구분하지 않습니다.

> 11주차 기술멘토링: "matching-queue 값을 가져올 때 필요한 정보만". 지금은 화면이 건수를 세려고도 O-22 로
> 모든 본문·후보를 받습니다. 화면이 O-28 로 옮겨 가면 O-22 목록에서 본문·후보·근거를 빼는 2단계를 따로 진행합니다.

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

- `assign`인데 `childId`가 없거나, 없는 아동이거나, 이 기관 소속이 아님 — 없는 아동과 다른 기관 아동은 **같은 메시지**로
  거절합니다(#331). 나누면 번호를 바꿔 가며 다른 기관에 어떤 아이가 있는지 알 수 있습니다
- `assign`으로 고른 아동이 **이 기관에 동의하지 않음** — AI 매칭 명단과 같은 기준. 다른 기관에만 동의해 `status`가
  `active`인 아이도 거절합니다. 보호자가 동의를 철회한 뒤에는 새 확정이 성공하지 않습니다(이미 확정된 기록은 그대로 둡니다)
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
- 나머지 필드(`confidence` 등)는 AI 판정 값 그대로입니다. `candidates`는 O-22와 같이 우리 기관 명부에 있는 아이만 나옵니다

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `MATCHING_RESULT_INVALID_REQUEST` | 위 거절 조건, 지원하지 않는 `action` |
| 400 | `INVALID_REQUEST` | 본문이 JSON이 아니거나 `childId`를 숫자로 읽을 수 없음 |
| 403 | `ORGANIZATION_NOT_ASSIGNED` | 기관 회원이 아님 |
| 403 | `FORBIDDEN` | `X-XSRF-TOKEN` 헤더 누락·불일치 (§1-2) |
| 404 | `MATCHING_RESULT_NOT_FOUND` | 없는 항목이거나 다른 기관의 항목 |
| 429 | `AGENT_QUEUE_FULL` | (`assign`) 그 기관 AI 처리 줄이 꽉 참 — 앞서 올린 기록 처리가 끝나면 다시 (#424, O-20 참고). `not_ours` 는 받지 않습니다 |

**O-24 응답**

```json
[
  { "id": "1",
    "record": { "id": "5", "fileName": "0821_특이사항.txt", "type": "특이사항",
                "capturedAt": "2026-08-21T16:00:00+09:00", "preview": "…" },
    "childName": "김하늘",
    "violationReason": "다른 아이의 이름이 함께 적혀 있어요" }
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
>     "violationReason": "전화번호·주민번호 같은 개인정보가 그대로 적혀 있어요",
>     "violationCode": ["개인정보표현"] }
> ]
> ```
>
> | 항목 | 값 |
> | --- | --- |
> | `violationReason` | AI가 보낸 `issue_types`를 선생님이 읽을 문장으로 바꾼 값. 유형이 여러 개면 ` / `로 잇습니다. AI가 목록에 없는 유형을 보내면 코드를 그대로 보여주고, 유형 자체가 없으면 "검증에서 문제가 발견됐어요. 원본을 확인해 주세요"로 내려갑니다. 유형 → 문장 표는 O-30 `reviewFlags[].reasons` 와 같습니다 (`ValidationReasons`) |
> | `violationCode` | AI `issue_types` 원본 배열 (`진단명`·`개인정보표현` 등, `AI/validation/config.py`) |
> | `record.fullContent` | 위반 문장이 60자 뒤에 있으면 미리보기만으로는 무엇을 고쳐야 할지 알 수 없어, 매칭 확인 큐(#111)와 같은 이유로 전체 본문도 함께 내려갑니다 |
> | `record.type` | **내려가지 않습니다.** `RecordResponse`에 `type` 필드가 없습니다 — 기록 유형 값 목록이 아직 팀에서 안 정해졌습니다 |
> | `capturedAt` | 기록 날짜(`YYYY-MM-DD`)입니다. 초안의 타임스탬프와 다릅니다 |
>
> ✅ **FE 반영됨 (2026-10-17, `feat/fe/blocked-queue-real`)** — 아래 두 가지 모두 반영했습니다.
>
> 1. `BlockedItem.record` 가 `RawRecord` 타입이라 `type` 이 필수인데 BE 가 그 값을 주지 않습니다.
>    `MatchingItem.record` 가 이미 `type?: RawRecord["type"] | null` 로 인라인 정의해 피해 간 것과
>    같은 처리가 필요합니다. 지금 `queue-reinput.tsx` 는 `{item.record.type}` 을 그대로 렌더해서,
>    실연동으로 바꾸면 `"김하늘 · "` 뒤가 비는 가운뎃점만 남습니다(mock 에는 값이 있어 안 보입니다).
> 2. `record.fullContent` 를 `BlockedItem` 에 추가하고 화면에서 써 주세요. 지금은 `preview`(60자)만
>    렌더하고 있어서, 위반 문장이 뒤쪽에 있으면 선생님이 무엇을 고쳐야 할지 볼 수 없습니다 —
>    매칭 확인 큐에서 #111 → #104 로 함께 고쳤던 것과 같은 건입니다.
>
> **O-25 현재 구현** · 막힌 기록 하나를 두 가지로 처리합니다 (#357). 둘 다 성공하면 큐에서 빠집니다.
>
> | 처리 | 요청 | 일지 |
> | --- | --- | --- |
> | 이 기록 보류 | `POST /validation-results/{itemId}/resolve` · `{ "action": "hold" }` | `VALIDATION_HELD` — 여기서 끝 |
> | 고쳐서 다시 검사 | `POST /validation-results/{itemId}/revise` · `{ "content": "고친 본문 전체" }` | 본문을 고친 본문으로 바꾸고 `MATCHED`(검증 대기). 아이는 그대로 |
>
> 고친 기록은 검증을 다시 받습니다. 통과(PASS·REVIEW)하면 요약 대기가 되어 오늘의 업무 묶음 카드(O-34)에서 [요약]을 눌러야
> 요약되고, 또 막히면 최신 사유로 이 큐에 다시 뜹니다. 올린 원본 파일은 바뀌지 않습니다 — 처음 글은 원본 파일에서 찾고,
> 일지에는 마지막으로 고친 사람·시각만 남습니다.
>
> **고치지 않은 본문은 거절합니다**(앞뒤 공백만 다른 것 포함). 실수로 그대로 저장하는 것과 같은 글을 그대로 다시 돌려
> 판정이 바뀌길 기다리는 것을 막습니다. 한 글자만 바꾼 글은 막지 못합니다 — 고친 사람이 일지에 남습니다.
>
> **최신 판정 카드로만 처리합니다.** 이미 고쳐 다시 검사했는데 또 막혀 새 판정이 생겼으면 예전 카드의 `itemId` 는
> 400 `VALIDATION_RESULT_OUTDATED` 입니다 (목록을 새로 받으면 새 카드가 있습니다). 고치기는 아이가 아직 우리 명부에 있고 우리 기관 동의가 켜져 있어야 합니다
> — 고친 본문과 아이 이름이 다시 AI 검증으로 가기 때문입니다. 아니면 400 `VALIDATION_RESULT_CHILD_NOT_SHARED` 이고,
> 할 수 있는 일은 보류뿐입니다. 400 은 화면이 이유마다 다른 안내를 하도록 코드를 나눕니다.
>
> `{ "action": "reupload" }`(수정한 원본 다시 올리기)는 #357 부터 받지 않습니다(400). 파일을 통째로 다시 올려 같은 파일의
> 다른 기록까지 두 번 등록됐기 때문입니다.
>
> | 상태 | 코드 | 설명 |
> | --- | --- | --- |
> | 400 | `INVALID_REQUEST` | (revise) `content` 누락·빈 문자열·5000자 초과 (#424 — 고친 본문은 그대로 검증 AI 에 가므로 업로드한 기록 하나와 같은 길이까지) |
> | 400 | `VALIDATION_RESULT_CONTENT_UNCHANGED` | (revise) 고치지 않은 본문 (앞뒤 공백만 다른 것 포함) |
> | 400 | `VALIDATION_RESULT_CHILD_NOT_SHARED` | (revise) 아이가 삭제됐거나 우리 명부에서 빠졌거나 우리 기관 동의가 꺼짐 — 보류만 가능 |
> | 400 | `VALIDATION_RESULT_OUTDATED` | (공통) 그 기록의 최신 판정이 아닌 카드 — 목록을 새로 받아 새 카드로 처리 |
> | 400 | `VALIDATION_RESULT_INVALID_REQUEST` | (resolve) `action` 누락·오타·`reupload` · (공통) BLOCK이 아닌 결과, 이미 처리된 기록 |
> | 404 | `VALIDATION_RESULT_NOT_FOUND` | 없는 항목이거나 다른 기관의 항목. 다른 기관 항목은 행을 잠그지 않고 거절합니다 |
> | 429 | `AGENT_QUEUE_FULL` | (revise) 그 기관 AI 처리 줄이 꽉 참 — 앞서 올린 기록 처리가 끝나면 다시 (#424, O-20 참고) |
>
> 같은 기록을 두 번 처리하면(창 두 개·연타 등) 일지 행을 잠가 차례로 처리하고, 나중 요청은 "이미 처리된 기록"(400)이 됩니다.

**O-25 요청** · 보류 `{ "action": "hold" }` · 고쳐서 다시 검사 `{ "content": "…" }`

**O-26 응답** · **구현됨** (#210). 우리 기관이 올린 파일만, 최근 업로드가 먼저 옵니다. **최근 50건**까지입니다 — 파일마다 일지를 함께 내려서 전부 주면 응답이 한 학기 치가 됩니다. 더 거슬러 봐야 하면 알려주세요, 페이징을 넣겠습니다.

> 묶음 카드(O-34)가 이 API 를 대체하지 않습니다 (#182). 같은 일지를 다르게 묶어 봅니다 — O-26 은 **파일별**로 "올린 파일이
> 잘 들어갔나"(기록 등록 화면), O-34 는 **아이 × 날짜별**로 "무엇을 해야 하나"(오늘의 업무)입니다. [요약] 은 묶음 단위라
> O-34 카드에 둡니다.

```json
[
  { "rawRecordId": "1", "fileName": "0821_관찰일지.docx",
    "uploadedAt": "2026-08-21T11:40:00",
    "entries": [ { "id": "1", "stageIndex": 1, "state": "waiting" } ] }
]
```

`state`: `running` 자동 진행 중 · `waiting` 사람 확인 대기 · `failed` 시스템 오류 · `done` 완료

> 파일을 기록 단위로 나누는 작업이 끝나기 전까지는 `entries: []` 만 내려옵니다.
> 화면은 "기록 등록 중"에 머무는데, 당분간 이 상태가 정상입니다 (PDF 가 여기 해당합니다).

`stageIndex` 는 O-21 의 단계 목록과 같습니다 — `0` 등록 · `1` 매칭 · `2` 검증 · `3` 요약 · `4` 1차 검토.

| `state` | 언제 |
| --- | --- |
| `running` | 워커가 자동으로 진행 중입니다. 기다리면 됩니다. 자동 요약(#369, 기본 켜짐)이면 요약 대기(`stageIndex` 3)도 여기입니다 |
| `waiting` | **사람이 봐야 넘어갑니다** — 매칭 확인 큐 · 수정 요청 큐 · 동의 대기 · Gate 1. 자동 요약이 꺼져 있으면 요약 대기(`stageIndex` 3, [요약]을 눌러야 넘어감)도 여기입니다 |
| `failed` | 처리하다 멈췄습니다 — 매칭 호출 실패(`FAILED`, 매칭 확인 큐), 요약 근거 0개(`SUMMARY_FAILED`, 다시 요약할 수 있음), 시스템 오류(`SYSTEM_ERROR`, 관리자 확인) (#180) |
| `done` | 그 일지의 파이프라인이 끝났습니다 |

> ⚠️ **`done` 이 전부 "승인됨" 은 아닙니다.** 교사가 뺀 기록(`EXCLUDED`), 고친 파일을 다시 올리기로 한
> 기록(`REUPLOAD_REQUESTED`, #357 전에 처리된 것만), 보류한 기록(`VALIDATION_HELD`)도 `done` 입니다. 요약 실패를 요약 없이
> 마무리한 기록(O-37)은 `stageIndex` 3 · `done` 입니다 — 1차 검토를 지나지 않았습니다. 셋 다 아동 맥락에
> 올라가지 않는데 승인된 것과 같아 보입니다. 화면에서 구분이 필요하면 알려주세요 — 칸을 더하겠습니다 (#210).

> **`failed` 의 `stageIndex`** — `SUMMARY_FAILED` 는 요약(`3`), `SYSTEM_ERROR` 는 DB 에 적힌 멈춘 단계(검증 `2` ·
> 요약 `3`)입니다 (#180). `FAILED` 만 적는 칸이 없어서 일지가 남긴 흔적(검증 판정 · 확정된 아동)으로 되짚습니다 —
> #180 부터는 매칭 실패만 `FAILED` 지만, 그 전에 검증·요약에서 `FAILED` 로 남은 일지가 있을 수 있습니다.

> **`uploadedAt` 에 시간대가 없습니다** (`2026-08-21T11:40:00`). 서버 시각 기준 한국 시간입니다 —
> `GET /raw-records` 의 `createdAt` 과 같은 형식입니다. `new Date(...)` 로 바로 파싱하면 브라우저에
> 따라 UTC 로 읽어 9시간이 틀어지니 주의해주세요.

**O-27** · **만들지 않습니다** (#181). 파일 단위로 `failed` 기록을 한꺼번에 다시 돌리는 대신, 실패는 원인별로
처리하는 곳이 따로 있습니다 (#180).

| 실패 | 어디서 처리 |
| --- | --- |
| 매칭 실패 (`FAILED`) | 확인 필요 큐 (O-22 · O-23) |
| 요약 실패 (`SUMMARY_FAILED`) | 검토 화면에 요약 실패 묶음(O-35)으로 띄워 [다시 요약](O-36) · [요약 없이 마무리](O-37) 를 둡니다 (#369) |
| 시스템 오류 (`SYSTEM_ERROR`) | 관리자 확인. 묶음 카드(O-34)에는 "관리자 확인 중" 건수만 보입니다 |

업로드 화면의 [다시 시도] 버튼(`retryFailedEntries`)은 FE 에서 이미 뺐습니다.

### 4.4 Gate 1 — 1차 검토

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-30 | GET | `/summaries?gate1Status=pending\|hold` | 검토 대기 목록 · 보류함 · **구현됨** | I-03, I-08 |
| O-31 | POST | `/summaries/{summaryId}/gate1` | 승인 / 수정 후 승인 / 보류 / 제거 / 확인·요약 되돌리기(요약할 내용 없음) · **구현됨** | I-08 |
| O-32 | POST | `/summaries/run` | 요약 · **구현됨** | I-08 |
| O-33 | POST | `/summaries/{summaryId}/reopen` | 보류 해제 · **구현됨** | I-08 |
| O-34 | GET | `/summaries/groups` | 묶음 카드 목록 (오늘의 업무) · **구현됨** | I-03, I-08 |
| O-35 | GET | `/summaries/failed` | 요약 실패 목록 · **구현됨** (#369) | I-08 |
| O-36 | POST | `/summaries/failed/retry` | 요약 실패 다시 요약 · **구현됨** (#369) | I-08 |
| O-37 | POST | `/summaries/failed/close` | 요약 실패 요약 없이 마무리 · **구현됨** (#369) | I-08 |

> O-30 · O-31 · O-33 의 요청·응답은 Gate 1 문장 카드 화면(FE #240)에 맞춰 확정했습니다 (#183).

**O-32 요청**

**자동 요약이 기본입니다** (#369, `SUMMARY_AUTO_RUN`, 기본 켜짐). 같은 날짜에 매칭·검증 중인 기록이 없으면 누르지
않아도 요약이 만들어지므로, 켜져 있을 때 이 요청은 무해한 "지금 요약" 입니다. 자동 요약을 끄면(장애 대응) 요약은
선생님이 아동 × 날짜 × 기관 묶음에 [요약] 을 눌러야 만들어집니다 (`docs/db/schema.md` §8.2). 기관은 요청한 선생님의 기관입니다.

```json
{ "childId": 8, "entryDate": "2026-10-08" }
```

응답 `data` 는 `null` 입니다. 요약은 곧이어 워커가 만들고, 같은 날짜에 매칭·검증 중인 기록이 있으면 그것이 끝난 뒤에
만듭니다. 그래서 업로드 직후에 눌러도 됩니다. 요청은 서버에 저장되어 재시작해도 남습니다. 자동 요약이 꺼져 있으면 요약하는
사이 들어온 새 기록은 요약이 끝난 뒤 다시 눌러야 요약됩니다 (승인 뒤 새 기록도 같습니다). 요약하는 중에는 받지 않습니다(409).
결과는 O-30 에서 확인합니다.

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| `childId`·`entryDate` 누락, 날짜 형식 오류 | 400 | `INVALID_REQUEST` |
| 그 아이·날짜에 요약할 기록도, 매칭·검증 중인 기록도 없음 | 400 | `SUMMARY_INVALID_REQUEST` |
| 없는 아이이거나 다른 기관 소속, 또는 보호자가 우리 기관 동의를 철회함 (#332) | 404 | `SUMMARY_CHILD_NOT_FOUND` |
| 그 묶음을 요약하는 중 — 요약이 끝나 1차 검토로 간 뒤 남은 기록은 묶음 카드(O-34)에서 다시 누름 | 409 | `SUMMARY_IN_PROGRESS` |

**O-30 응답**

우리 기관의 요약을 오래된 날짜부터 줍니다. `gate1Status` 는 `pending`(기본값, 검토 대기)과 `hold`(보류함)만
받고 다른 값은 `400 SUMMARY_INVALID_REQUEST` 입니다. 보호자가 우리 기관 동의를 철회한 아이의 요약은 철회 전에
만들어졌어도 두 목록 모두에서 빠집니다. 다시 동의하면 다시 나옵니다 (#332).

```json
[
  { "id": 50, "childId": 8, "childName": "김하늘", "entryDate": "2026-10-08", "revision": 1,
    "status": "GATE1_PENDING", "version": "2026-10-09T03:05:12.345678/1041,1042", "processing": false,
    "content": "오전 활동에서 블록을 다섯 층까지 쌓았다 …",
    "claims": [ { "index": 0, "text": "오전 활동에서 블록을 다섯 층까지 쌓았다.",
                  "evidence": [ { "journalEntryId": 1041, "quote": "블록을 다섯 층까지 쌓았다" } ] },
                { "index": 1, "text": "…", "evidence": [] } ],
    "noContent": false, "needsReview": true, "reviewReasons": ["다른아동이름"],
    "uncoveredEntries": [ { "journalEntryId": 1042, "content": "낮잠을 잤다." } ],
    "reviewFlags": [ { "journalEntryId": 1041, "issueTypes": ["추측성표현"],
                      "reasons": ["관찰되지 않은 내용을 추측한 표현이 있어요"], "claimIndexes": [0] } ],
    "entries": [ { "journalEntryId": 1041, "content": "오전 활동에서 블록을 다섯 층까지 쌓았다." },
                 { "journalEntryId": 1042, "content": "낮잠을 잤다." } ] }
]
```

| 필드 | 뜻 |
| --- | --- |
| `version` | 결정(O-31)할 때 그대로 돌려보내는 값(요약 수정 시각 + 요약에 든 일지 id). 해석하지 말고 문자열 그대로 보내주세요 |
| `processing` | 곧 이 요약이 바뀜. 지금 다시 요약 중이거나, 같은 날 이 아이 기록 중 아직 요약에 반영되지 않은 것이 있을 때(검증 중 포함) 켜집니다. 매칭 전이라 누구 기록인지 모르는 것이 같은 날 있어도 켜집니다. 자동 요약(#369, 기본 켜짐)이 꺼져 있으면 [요약](O-32)을 눌러 둔 경우에만 켜집니다 — 누르기 전까지 바뀌지 않기 때문입니다 (#181). 열기·승인 버튼을 막아주세요. 보류함 항목과 옛 판은 항상 `false` |
| `claims` | 요약 문장 카드. 카드마다 [유지]·[수정]·[삭제]를 두고, 결정(O-31) 때 `index` 를 그대로 돌려보냅니다 (#221). **빈 배열이면** 문장을 읽지 못한 요약이라 카드 편집([추가] 포함)을 막아 주세요 — 카드를 보내면 400 입니다. 그대로 승인하거나 제거만 됩니다. `noContent` 요약도 빈 배열이지만 그건 아래 `noContent` 를 따릅니다 |
| `claims[].evidence` | 그 문장의 근거 — AI 가 든 일지(`journalEntryId`)와 원문 인용(`quote`) (#370). 화면은 원본 일지에서 인용을 찾아 강조합니다. 인용은 원문 그대로가 원칙이지만 글자가 조금 다를 수 있어 공백·문장부호를 무시하고 찾고, 그래도 없으면 강조 없이 안내해 주세요. 선생님이 고치거나 새로 쓴 문장(`source` 가 `EDITED`·`TEACHER`)은 빈 배열입니다 — 지금 목록에는 승인 전 판만 나와 실제로는 AI 문장뿐입니다 |
| `noContent` | 그날 기록에 요약할 내용이 없다는 결과 (#244). `content` 는 `""`, `claims` 는 `[]` 이고 `uncoveredEntries` 가 **이 요약에 묶인 기록 원본 전체**입니다. "요약할 내용 없음" 카드로 보여주고 원본을 펼쳐 두세요 — 교사가 "왜 없지?" 를 바로 확인할 수 있게. 버튼은 [확인](O-31 `confirm`)과 [요약 되돌리기](O-31 `revert`) 둘만 둡니다 |
| `needsReview` · `reviewReasons` | 요약 본문에 다른 아이 이름이 남아 공유 전에 봐야 하는지와 그 이유. 막지 않고 경고만 합니다 |
| `uncoveredEntries` | 요약에 안 들어간 일지와 원문. **승인하면 이 일지도 함께 끝납니다** — "요약에 안 들어간 일지 N건"으로 보여주세요. AI 가 목록에 넣지 않은 일지도 여기에 나옵니다 |
| `reviewFlags` | 요약에 들어간 원본 중 검증에서 `REVIEW` 를 받은 일지와 그 사유. `issueTypes` 는 AI 코드 원본, `reasons` 는 선생님이 읽을 문장입니다. 받은 순서대로 바꾸되 같은 문장은 한 번만 남겨 **두 배열의 칸이 짝지어지지 않습니다**. **화면은 `reasons` 를 보여 줍니다** — 코드를 그대로 띄우지 않습니다 (#351). 유형이 없거나 깨졌으면 `reasons` 는 "검증에서 문제가 발견됐어요. 원본을 확인해 주세요" 한 줄입니다. 문장 표는 O-24 `violationReason` 과 같습니다 |
| `reviewFlags[].claimIndexes` | 그 일지의 검증 구간과 근거 구간이 겹친 문장 카드 번호(`claims[].index`), 오름차순 (#408). 화면은 그 카드에 경고를 붙이고 배너에 "N번 문장" 을 적습니다. **AI 문장만** 들고(카드가 근거를 보이는 문장과 같은 기준), 검증 구간이 없거나(`대상불명확` · `모델호출실패` 등) 카드로 못 읽는 요약이면 `[]` 입니다 — 그래도 경고는 그대로 나옵니다. **사유와 짝지어지지 않습니다** — 한 일지에 사유가 둘 이상이면 어느 문장이 어느 사유인지 모릅니다 |
| `entries` | 이 요약에 묶인 일지 원문 전체(요약에 든 것 + 안 든 것, id 순, 삭제된 일지 제외). 검토 화면은 원본 일지를 늘 펼쳐 두고(#371), 문장의 [근거]를 누르면 `claims[].evidence[].quote` 를 이 안에서 강조합니다(#372). 인용이 원문과 글자가 다르면 공백·문장부호를 무시하고 찾고, 그래도 없으면 강조 없이 "근거 위치를 못 찾았어요" 를 보입니다 |

`reviewFlags` 는 검증이 짚은 구간이 **요약 문장의 근거 구간과 같은 일지에서 겹칠 때만** 나옵니다. 요약에 안 실린
부분의 문제까지 띄우지 않기 위해서입니다. 검증 사유와 구간이 짝지어져 있지 않아 일지 단위로 판단하고, 겹치면 그 일지의
사유를 모두 줍니다. 검증 구간이 비어 있으면(어디가 문제인지 모름) 줍니다. 하이라이트(`flaggedSpan`)는 하지 않습니다.
사유 단위로 거르는 것은 검증 `issues` 필드가 생긴 뒤입니다(#179). 경고를 낼지 · 사유는 이 판정 그대로이고,
`claimIndexes` 는 판정이 끝난 경고에 겹친 문장 번호만 덧붙입니다 — 번호가 비어도 경고는 사라지지 않습니다.

**O-31 요청**

```json
{ "decision": "approve", "version": "2026-10-09T03:05:12.345678/1041,1042" }
```

```json
{ "decision": "approve", "version": "2026-10-09T03:05:12.345678/1041,1042",
  "cards": [
    { "index": 0, "action": "KEEP" },
    { "index": 1, "action": "DELETE" },
    { "index": 2, "action": "EDIT", "text": "블록을 두 번 쌓았다." },
    { "action": "ADD", "text": "약 먹은 날 차분했다." } ] }
```

```json
{ "decision": "hold", "version": "2026-10-09T03:05:12.345678/1041,1042" }
```

```json
{ "decision": "confirm", "version": "2026-10-09T03:05:12.345678/1041,1042" }
```

```json
{ "decision": "revert", "version": "2026-10-09T03:05:12.345678/1041,1042" }
```

| 필드 | 조건 |
| --- | --- |
| `decision` | `approve` \| `hold` \| `remove` \| `confirm` \| `revert` (반려는 없습니다 — 고쳐서 승인하거나 보류·제거합니다). `confirm`·`revert` 는 `noContent` 요약에만, 나머지는 그 밖의 요약에만 받습니다 |
| `cards` | `approve` 일 때만, 빈 배열 불가. 없으면 그대로 승인. 보류·제거·확인·되돌리기에 보내면 400. **배열 순서가 최종 문장 순서입니다** |
| `cards[].action` | `KEEP`(유지) \| `EDIT`(수정) \| `DELETE`(삭제) \| `ADD`(추가). 대문자 |
| `cards[].index` | O-30 `claims` 의 `index`. `KEEP`·`EDIT`·`DELETE` 는 필수, `ADD` 는 보내지 않음. **원래 문장은 모두 한 번씩** 보내야 합니다 |
| `cards[].text` | `EDIT` 은 고친 글, `ADD` 는 새 글 (빈 글 불가, 1000자까지). `KEEP`·`DELETE` 는 보내지 않음 |
| `version` | 필수. O-30 에서 받은 값 그대로 |

`editedContent`(고친 본문 전체)는 없어졌습니다 (#221). 보내면 버리지 않고 400 으로 막습니다 — 버리면 고친 글 대신
AI 원문이 승인됩니다.

| 결정 | 요약 | 그 요약의 일지 | 기록(`decision`) |
| --- | --- | --- | --- |
| 승인 (`cards` 없음, 또는 모두 원래 순서대로 `KEEP`) | 승인. AI 원문이 아동 타임라인에 올라감 | 요약에 안 들어간 일지까지 모두 완료 | `APPROVED` |
| 고쳐서 승인 (`KEEP` 이 아닌 카드가 있거나 순서가 바뀜) | 승인. 남은 문장을 순서대로 이은 글이 아동 타임라인에 올라감 | 위와 같음 | `CORRECTED` |
| 보류 | 보류함으로 | 함께 보류 (같은 날 새 기록이 와도 묶이지 않음) | `HOLD` |
| 제거 | 제거 | 모두 삭제. **되돌릴 수 없음** | `REMOVED` |
| 확인 (`noContent` 요약만) | 확인됨(`CONFIRMED`). 올릴 글이 없어 아동 타임라인에 올라가지 않음 | 모두 완료 | `CONFIRMED` |
| 요약 되돌리기 (`noContent` 요약만) | 되돌림(`REVERTED`). 목록에서 빠짐 | 요약 전으로 돌아감 — [요약](O-32)을 다시 누르면 새로 요약되고, 그 사이 들어온 같은 날 기록도 함께 묶임 | `REVERTED` |

승인과 제거는 되돌릴 수 없으니 확인 팝업을 띄워주세요. 고친 글과 새 글은 MVP 에서 다시 검증하지 않습니다.
`EDIT` 글이 원문과 줄바꿈 방식·앞뒤 공백만 다르면 고치지 않은 것(`KEEP`)으로 봅니다. 문장을 모두 지우려면
승인 대신 제거를 써주세요 — 남는 문장이 없으면 400 입니다.

고쳐서 승인하면 인사이트가 읽는 문장 목록(`summary_result.claims`)도 카드 결과로 바뀝니다. 지운 문장은 빠지고, 고친
문장과 새 문장에는 누가 썼는지(`EDITED`·`TEACHER`) 표시가 붙습니다 (DB 스키마 §8.2).

**보류함**은 보류 해제(O-33)와 제거만 둡니다. 보류된 요약을 승인하려면 보류를 해제한 뒤 검토 목록에서 합니다.

`409 SUMMARY_CHANGED` 를 받으면 "요약이 새 기록으로 바뀌었어요" 안내를 띄우고 목록으로 돌아가 다시 불러와 주세요 —
교사가 화면을 연 사이 같은 날 기록이 들어와 요약이 다시 만들어진 경우입니다.

**응답**

```json
{ "summaryId": 50, "status": "APPROVED", "decision": "CORRECTED" }
```

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| `decision`·`version` 누락, 허용되지 않은 값, 승인이 아닌데 `cards`, 빈 `cards`, 카드 `action` 에 맞지 않는 `index`·`text`, 카드 100장·글 1000자 초과, 옛 `editedContent` 를 보냄 | 400 | `INVALID_REQUEST` |
| 원래 문장 `index` 가 빠지거나 겹치거나 범위 밖, 남는 문장이 없음, 문장(`claims`)을 못 읽는 요약에 `cards` 를 보냄, `noContent` 요약에 `confirm`·`revert` 가 아닌 결정 또는 그 밖의 요약에 `confirm`·`revert` | 400 | `SUMMARY_INVALID_REQUEST` |
| 없는 요약이거나 다른 기관 요약, 또는 보호자가 우리 기관 동의를 철회한 아이의 검토 대기·보류 요약 (#332 — 요약은 그대로 남고 목록에서 빠짐. 이미 처리된 요약은 아래 409 가 먼저) | 404 | `SUMMARY_NOT_FOUND` |
| 이미 처리된 요약 | 409 | `SUMMARY_ALREADY_REVIEWED` |
| 보류된 요약을 승인·보류 (보류 해제 먼저) | 409 | `SUMMARY_HELD` |
| 같은 날 새 기록을 반영해 요약을 다시 만드는 중 — 잠시 뒤 다시 시도 | 409 | `SUMMARY_REGENERATING` |
| 목록에서 본 뒤 요약이 새 기록으로 바뀜 — 목록을 다시 불러오기 | 409 | `SUMMARY_CHANGED` |
| (`revert`) 그 기관 AI 처리 줄이 꽉 참 — 앞서 올린 기록 처리가 끝나면 다시 (#424, O-20 참고) | 429 | `AGENT_QUEUE_FULL` |

**O-33 보류 해제** · 본문 없음

보류함의 요약과 그 일지를 다시 검토 대기로 돌립니다. 응답은 O-31 과 같은 모양입니다
(`{ "summaryId": 50, "status": "GATE1_PENDING", "decision": "REOPENED" }`).

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| 없는 요약이거나 다른 기관 요약, 또는 보호자가 우리 기관 동의를 철회한 아이의 검토 대기·보류 요약 (#332 — 이미 처리된 요약은 `SUMMARY_NOT_HELD` 가 먼저) | 404 | `SUMMARY_NOT_FOUND` |
| 보류된 요약이 아님 | 409 | `SUMMARY_NOT_HELD` |
| 같은 날 새 기록을 반영해 요약을 다시 만드는 중 — 잠시 뒤 다시 시도 | 409 | `SUMMARY_REGENERATING` |

**O-34 응답** · **구현됨** (#182)

오늘의 업무 화면의 묶음 카드입니다. 카드 1장 = 아이 1명의 하루치 기록(아동 × 날짜, 기관은 요청한 선생님의 기관).
**요약 전 묶음만** 옵니다 — 요약이 만들어지면 그 묶음은 1차 검토(O-30)로 옮겨 가고 카드는 빠집니다. 남은 카드가 곧
밀린 일이라 날짜로 자르지 않고 전부 줍니다(오래된 날짜부터). 탭·기간 나누기는 화면에서 해주세요.

```json
{
  "matching": { "inProgress": 5, "needsReview": 2 },
  "groups": [
    { "childId": 12, "childName": "김하늘", "birthdate": "2019-03-02", "entryDate": "2026-09-17",
      "status": "needs_fix",
      "counts": { "validating": 0, "needsFix": 1, "ready": 2, "summarizing": 0, "adminCheck": 1 },
      "canSummarize": true, "summaryRequested": false, "waitingCount": 0, "previousSummaryInReview": false }
  ]
}
```

| 필드 | 뜻 |
| --- | --- |
| `matching` | 아이가 아직 정해지지 않아 카드에 넣을 수 없는 기록. `inProgress` 자동 매칭 중, `needsReview` 확인 필요 큐(O-22)에서 아이를 골라야 함. 화면 맨 위 한 줄로 보여주세요 |
| `childName` · `birthdate` | 같은 이름의 아이를 구분할 수 있게 생년월일도 줍니다 |
| `status` | 카드 라벨. 아래 표 |
| `counts` | 상태별 기록 건수. `validating` 검증 대기·검증 중, `needsFix` 수정 요청 큐(O-24)에 있음, `ready` 요약 대기, `summarizing` 요약 중, `adminCheck` 시스템 오류로 관리자 확인 중 |
| `canSummarize` | [요약](O-32)을 누를 수 있음. 요약 대기 기록이 있고, 누르지 않았고 요약 중도 아닐 때. 버튼은 항상 두고 `false` 면 막아주세요 |
| `summaryRequested` | [요약]을 눌렀고 아직 요약이 끝나지 않음. 요약이 시작되기 전에 들어온 요약 대기 기록은 함께 요약됩니다. 요약 중에 들어온 기록은 이번 요약에 없습니다 — 요약이 끝나 1차 검토로 간 뒤 이 카드에 요약 대기로 남고(`previousSummaryInReview: true`), 그때 [요약]을 다시 누를 수 있습니다 |
| `waitingCount` | 눌렀지만 아직 시작 전일 때, 요약이 기다리는 같은 날 매칭·검증 중 기록 수. "같은 날 기록 N건 처리 뒤 시작" 처럼 보여주세요. 그 밖엔 `0` |
| `previousSummaryInReview` | 이 묶음의 이전 요약이 1차 검토에 있음 — 그 뒤 새 기록이 들어온 경우. [요약]을 누르면 1차 검토의 최신 요약에 든 기록까지 합쳐 새 판으로 다시 요약하고, 그 요약이 새 판으로 바뀝니다 |

`status` 는 교사가 할 일을 먼저 보여줍니다. 위에서부터 처음 맞는 것입니다.

| `status` | 언제 | 화면 |
| --- | --- | --- |
| `needs_fix` | 수정 요청 큐에 있는 기록이 있음 | "수정 요청 N건". 요약 대기 기록이 함께 있으면 [요약]은 누를 수 있지만 권하지 않게(약하게) 보여주세요 — 고친 기록은 다음 요약에 들어갑니다 |
| `ready` | 요약 대기 기록이 있고 아직 누르지 않음 | [요약] 강조. 검증 중 기록이 섞여 있어도 지금 눌러도 됩니다 — 검증이 끝난 뒤 함께 요약합니다 |
| `summarizing` | 요약 중이거나, [요약]을 눌러 둠 | 진행 중 |
| `validating` | 검증 대기·검증 중 기록만 있음 | 진행 중 |
| `admin_check` | 시스템 오류 기록만 남음 | "관리자 확인 중 N건". 교사가 할 일은 없습니다 |

> **화면 갱신** — 자동으로 바뀌는 기록이 있는 동안만 몇 초마다 다시 불러와 주세요: 어느 카드든 `counts.validating`·
> `counts.summarizing` 이 0보다 크거나 `summaryRequested` 가 `true`, 또는 `matching.inProgress` 가 0보다 클 때입니다.
> 라벨로 판단하지 마세요 — `needs_fix` 카드 안에서도 요약이 돌 수 있습니다. 모두 사람 대기로 멈추면 그만 불러도 됩니다
> (기록 등록 화면의 O-26 폴링과 같은 방식).

카드에 넣지 않는 기록: 요약 실패(`SUMMARY_FAILED`, O-35 로 검토 화면에 뜸), 수정 요청 큐에서 [보류] 한
기록(#357 전에 [다시 올리기] 한 기록 포함. [고쳐서 다시 검사] 한 기록은 검증을 통과하면 다시 카드에 들어옵니다), 1차 검토·보류함에 있는 기록, 완료·제외된 기록. 기관에서 뺀 아이와 보호자가 우리 기관 동의를 철회한 아이의 기록도
넣지 않습니다 — [요약]이 거절합니다(404). 동의를 철회하기 전에 요약을 눌러 뒀어도 워커가 요약하지 않고 요청을 닫습니다 (#332).

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| 기관 소속이 아닌 사용자 | 403 | `ORGANIZATION_NOT_ASSIGNED` |

**O-35 응답** · **구현됨** (#369)

근거가 남은 문장이 없어 재시도 뒤에도 요약하지 못한 기록(`SUMMARY_FAILED`)을 아동 × 날짜 묶음으로 오래된 날짜부터
줍니다(같은 날짜는 아이 id 순). 요약 행이 없어 O-30 에 섞지 않고 따로 받습니다 — 검토 화면에 "요약에 실패했어요" 카드로
띄워 주세요. 기관은 요청한 선생님의 기관이고, 보호자가 우리 기관 동의를 철회한 아이의 묶음은 빠집니다(O-30 과 같은 기준, #332).

```json
[
  { "childId": 8, "childName": "김하늘", "entryDate": "2026-10-08",
    "entries": [ { "journalEntryId": 1041, "content": "블록 놀이를 했다." } ],
    "reason": "NO_GROUNDED_CLAIMS" }
]
```

| 필드 | 뜻 |
| --- | --- |
| `entries` | 요약하지 못한 기록과 원문(id 순). 카드에 원본을 펼쳐 보여 주세요 |
| `reason` | 실패 원인 코드. 지금은 `NO_GROUNDED_CLAIMS`(요약 문장의 근거를 원문에서 찾지 못함) 하나입니다 |

시스템 오류(`SYSTEM_ERROR`)는 다시 돌려도 같아 여기 오지 않습니다 — 관리자가 처리합니다.

**O-36 · O-37 요청** · **구현됨** (#369)

```json
{ "childId": 8, "entryDate": "2026-10-08" }
```

응답 `data` 는 `null` 입니다. 그 묶음의 `SUMMARY_FAILED` 기록만 바꿉니다.

- **O-36 다시 요약** — 기록을 요약 대기(`VALIDATED`)로 돌립니다. 워커가 곧 다시 요약하고 결과는 O-30 에 옵니다. 같은 묶음에
  검토 대기 요약이 있으면 그 요약과 함께 다시 만듭니다. 횟수 제한은 없습니다 — 또 실패하면 O-35 에 다시 뜹니다.
- **O-37 요약 없이 마무리** — 기록을 끝냅니다(`COMPLETED`). O-31 "요약할 내용 없음" 확인과 같은 끝으로, 보호자·아동 맥락·
  인사이트로 가지 않습니다. 되돌릴 수 없습니다.

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| `childId`·`entryDate` 누락, 날짜 형식 오류 | 400 | `INVALID_REQUEST` |
| 보호자가 우리 기관 동의를 하지 않았거나 철회한 아이, 없는 아이 | 404 | `SUMMARY_CHILD_NOT_FOUND` |
| 그 묶음에 요약 실패 기록이 없음 — 이미 처리했거나 다른 기관 기록. 목록을 다시 불러와 주세요 | 404 | `SUMMARY_FAILED_NOT_FOUND` |
| (O-36) 그 기관 AI 처리 줄이 꽉 참 — 앞서 올린 기록 처리가 끝나면 다시 (#424, O-20 참고) | 429 | `AGENT_QUEUE_FULL` |

### 4.5 Insight · Gate 2

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-40 | GET | `/insights` | Insight 목록 | I-03, I-10, I-11 |
| O-41 | GET | `/insights/{id}` | Insight 단건 | I-10 |
| O-42 | POST | `/insights/{insightId}/gate2` | 발송 승인 / 보류 | I-11 |
| O-43 | GET | `/insights/progress` | 인사이트 진행 현황 · **구현됨** | I-03 |

> **2026-10-20 수정 (#206)** — 10/17 회의에서 **열람 방식**과 **보호자가 수신 기관을 고르는 흐름**으로
> 정해져, `primarySource` 규칙과 O-42 요청 바디가 바뀌었습니다. 아래가 구현된 모양입니다.

**O-40 요청** · `?gate2Status=pending|approved|hold` (기본 `pending`)

**O-40 응답** · 우리 기관이 **승인 주체인** 인사이트만 내려갑니다. 최신 회차만 보입니다.

```json
[
  { "id": "1", "childId": "1", "childName": "김하늘",
    "period": "2026년 8월 3주차",
    "content": "오후 시간대 …",
    "needsReview": true,
    "teacherWritten": false,
    "primarySource": { "id": "2", "name": "햇살아동발달센터",
                       "type": "center", "verified": false },
    "evidence": [
      { "childContextId": "10", "date": "2026-08-19", "label": "관찰일지 요약",
        "institution": { "id": "2", "name": "햇살아동발달센터",
                         "type": "center", "verified": false },
        "lookedUp": false }
    ],
    "targets": [
      { "institution": { "id": "1", "name": "○○초등학교",
                         "type": "school", "verified": false }, "consent": "granted" },
      { "institution": { "id": "3", "name": "△△활동지원",
                         "type": "assistant", "verified": false }, "consent": "not_granted" }
    ],
    "gate2Status": "pending" }
]
```

**`primarySource` = 기관 종류 우선순위로 뽑은 한 기관입니다.**

```
학교 > 센터 > 활동지원사        같은 종류가 둘이면 organization.id 가 작은 쪽
```

근거를 낸 기관(`source_institution_ids`) 중에서 고릅니다. **이 기관만 Gate 2 를 승인할 수 있고**,
목록에도 이 기관에만 보입니다. 서버가 같은 조건으로 인가합니다.

| 요청 기관 | 결과 |
| --- | --- |
| `primarySource` | 조회·승인 가능 |
| 근거는 냈지만 `primarySource` 가 아님 | `403 INSIGHT_NOT_PRIMARY_SOURCE` |
| 근거를 내지 않은 기관 | `404 INSIGHT_NOT_FOUND` — 존재 여부를 알려주지 않습니다 |

> 이전 명세는 "근거 요약을 **최다** 제공한 기관" 이었습니다. 최다를 구하려면
> `claim_ids → summary_result → institution_id` 로 거슬러 세야 하는데, 종류 우선순위는
> `source_institution_ids` + `organization.type` 으로 바로 나옵니다 (#206 에서 변경).

**`needsReview` 는 꼭 표시해주세요.** 인사이트 본문에 **다른 아이 이름이 없다는 보장이 없고**,
Gate 1 에서 교사가 **검증을 거치지 않고 고치거나 새로 쓴 문장**이 근거로 섞일 수 있습니다
(`AI/insight/CRITERIA.md` §5, #199). 기관 경계를 넘는 마지막 관문이라 승인자가 알아야 합니다.

**`teacherWritten` 이 `true` 면 "⚠️ 검증을 거치지 않은 교사 작성 문장이 포함되어 있습니다" 를 띄워주세요** (#323).
근거(대처 근거 포함) 중 교사가 Gate 1 에서 고치거나(`EDITED`) 새로 쓴(`TEACHER`) 문장이 하나라도 있으면 `true` 입니다
(CRITERIA §5 `teacher_claim_ids` 와 같은 판단). 근거 번호가 없거나 서버가 근거 문장을 찾지 못하면 확인할 수 없어 `true` 로 보냅니다.
`needsReview` 는 그대로 모든 글이 `true` 입니다.

**`evidence[].lookedUp` 이 `true` 인 근거는 이번 기간이 아니라 AI 가 지난 기록에서 찾아 쓴 것입니다** (#387).
하나라도 있으면 "지난 기록을 찾아 씀" 을 표시하고, 그 근거를 날짜 · 기관과 함께 "지난 기록" 으로 이번 주 근거와 구분해 주세요(#388).
지난 기록은 이번 기간 앞 12주치의 Gate 1 승인 요약 중 주간 인사이트 동의 기관 것만 AI 에 보냅니다. 대처 근거에만 쓰인 지난 기록도
`evidence` 에 들어옵니다. 지금은 AI 쪽 도구 사용이 꺼져 있어 모두 `false` 로 옵니다.

**`targets` 는 승인하면 이 글을 받을 후보 기관입니다** (#233). 그 아이 명부에 있는 기관 중 **승인 기관
자신만 뺀 것**이며(재료를 낸 다른 기관도 받습니다), 그중 **`consent` 가 `granted` 인 기관만 실제로 받습니다.**
**`consent` 는 주간 인사이트 공유 동의 기준입니다** (#257) — 보호자가 하루 요약만 허락한 기관은 `not_granted` 로 옵니다. 미동의 기관도 함께 오니 프론트는
숨기지 않고 **잠금 + 사유 표시**로 보여줍니다. 수신처를 고르는 단계는 없습니다 — 기관도 보호자도 고르지
않고, 보호자가 미리 해 둔 기관별 동의를 그대로 씁니다.

**O-42 요청**

```json
{ "decision": "approve" }
```

`decision` 은 `approve` 또는 `hold` 입니다. `targetInstitutionIds` 는 **없습니다** — 받는 기관은 보호자의
기관별 공유 동의로 정해집니다.

| 규칙 | 내용 |
| --- | --- |
| 승인하면 | 인사이트가 `approved` 가 되고 **보호자와, `targets` 중 동의가 `granted` 인 기관에 열람 권한이 생깁니다** (#233) |
| 승인 뒤에 동의한 기관 | 지난 글은 받지 않습니다. 동의 뒤에 승인된 글부터 받습니다 |
| 보호자가 없으면 | 승인은 되지만 아무에게도 보이지 않습니다. 오류가 아닙니다 |
| 동의가 그사이 꺼졌으면 | 인사이트를 만들 때는 동의가 있었어도 **승인 시점에 다시 봅니다.** 재료 기관 중 하나라도 꺼져 있으면 `409` 입니다 — 여러 기관 기록을 엮은 글이라 일부만 뺄 수 없습니다. **보류는 됩니다** (아무 데도 안 나가므로) |
| 되돌리기 | **승인은 취소할 수 없습니다.** 취소 API 는 만들지 않습니다 |
| 승인 뒤 동의가 꺼지면 | **열람 권한이 거둬집니다** (#213). 다만 `gate2Status` 는 `approved` 그대로라 목록에서는 구분되지 않습니다 — 화면에 표시가 필요하면 알려주세요 |
| 보류 | `hold` 는 나가지 않습니다. 보류 해제 API 는 Gate 2 에 없습니다 (Gate 1 과 다른 점) |

**응답** · `{ "insightId": "1", "gate2Status": "approved", "decision": "APPROVED" }`

**실패**

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| `decision` 누락·허용되지 않은 값 | 400 | `INVALID_REQUEST` |
| 지원하지 않는 `gate2Status` | 400 | `INSIGHT_INVALID_REQUEST` |
| 없는 인사이트, 근거를 내지 않은 기관, 지금 우리 명부에 없는 아이 | 404 | `INSIGHT_NOT_FOUND` |
| 근거는 냈지만 승인 주체가 아님 | 403 | `INSIGHT_NOT_PRIMARY_SOURCE` |
| 이미 승인·보류된 인사이트 | 409 | `INSIGHT_ALREADY_REVIEWED` |
| 같은 기간의 새 회차가 만들어짐 | 409 | `INSIGHT_SUPERSEDED` |
| 재료 기관의 공유 동의가 철회됨 | 409 | `INSIGHT_MATERIAL_CONSENT_REVOKED` |

**O-41** · 단건. 응답은 O-40 의 원소 하나와 같습니다. 인가도 같습니다 — 근거를 냈고, 승인 주체이고,
**그 아이가 지금도 우리 기관 명부에 있어야** 합니다. 셋 중 하나라도 아니면 404/403 입니다.

**재료 기관의 동의가 꺼진 글은 목록(O-40)에서 빠지고 단건(O-41)은 404 입니다** (#257, 멘토님 리뷰 #256 P1).
`pending`·`approved` 모두 같습니다. 범위에서 `weekly_insight` 를 뺀 것도 꺼진 것으로 봅니다. 여러 기관 기록을 엮은
글이라 일부만 가릴 수 없어 통째로 뺍니다.

> 숨긴 대기 글은 상태를 바꾸지 않고 대기로 남습니다. 아무에게도 나가지 않고, 보호자가 동의를 다시 켜면 다시
> 보입니다. 그래서 id 로 직접 결정(O-42)을 보내면 **승인은 `409 INSIGHT_MATERIAL_CONSENT_REVOKED`, 보류는 됩니다** —
> 화면이 숨기기 전에 받아 둔 목록에서 누른 경우입니다.

**다른 점은 회차 하나뿐입니다.** O-40 은 최신 회차만 보여주지만, O-41 은 ID 로 직접 물으면 옛 회차도
보여줍니다. 승인은 막힙니다(`409 INSIGHT_SUPERSEDED`).

> ⚠️ **연결이 끊기면 그 뒤로 안 보입니다.** `source_institution_ids` 는 인사이트를 만들 때 굳은 값이라
> 아이가 기관을 떠나도 그대로 남습니다. 그것만 보면 연결이 끊긴 기관이 계속 읽으므로, 조회·승인 모두
> **그때그때 명부를 다시 봅니다.**

**O-43 인사이트 진행 현황** (2026-11-05 추가) · Gate 1 을 승인한 뒤 Gate 2 에 글이 뜨기까지 화면에 아무 변화가
없어서, 승인한 기록이 지금 어느 단계인지 알려 줍니다.

- **우리 기관이 최근 14일(`app.insight.lookback-days`) 안에 Gate 1 에서 승인한** 기록이 속한 아이 × 주만 나옵니다.
  다른 기관만 승인한 주는 나오지 않습니다.
- 지금 **우리 명부에 있고 공유 동의가 켜진 아이**만 나옵니다 (Gate 1 목록과 같은 기준).
- 최근 주부터, 같은 주는 아이 이름 순입니다. 요청 파라미터는 없습니다.

```json
[
  { "childId": "1", "childName": "김하늘",
    "periodFrom": "2026-08-17", "periodTo": "2026-08-23", "period": "2026년 8월 3주차",
    "status": "done", "message": "주간 리포트가 나와 승인 차례로 넘어갔어요" }
]
```

`message` 는 화면에 그대로 쓰는 문장입니다. `status` 는 아래 중 하나이고, 위에서부터 먼저 맞는 것으로 정합니다.

| `status` | 언제 | `message` |
| --- | --- | --- |
| `not_consented` | 보호자가 우리 기관에 **주간 인사이트** 공유를 허락하지 않음 (#257). 우리 기록은 재료가 되지 않음 | 보호자가 주간 리포트 공유를 허락하지 않아 우리 기관 기록은 주간 리포트에 쓰지 않아요 |
| `waiting_week_end` | 끝나지 않은 이번 주. 운영은 주가 끝난 뒤 분석합니다 (`INSIGHT_INCLUDE_CURRENT_WEEK=false`) | 이번 주가 끝나면 주간 리포트를 만들어요 |
| `queued` | 아직 분석 전이거나, 지난 분석 뒤 새로 승인된 기록이 있어 다시 분석할 차례. 서버가 실제로 분석할 재료가 있을 때만 | 곧 주간 리포트를 만들기 시작해요 |
| `running` | 분석 중 | 주간 리포트를 만들고 있어요 |
| `retrying` | 마지막 분석이 실패함. 서버가 잠시 뒤 다시 시도합니다 | 주간 리포트를 만들다 잠시 실패해 다시 시도할 예정이에요 |
| `done` | 끝났고 인사이트가 1건 이상. 승인 주체 기관의 Gate 2 로 넘어감 | 주간 리포트가 나와 승인 차례로 넘어갔어요 |
| `nothing_new` | 끝났지만 인사이트 0건(정상입니다, `AI/insight/CRITERIA.md` §3). 또는 동의한 기관의 분석 재료가 없어 분석하지 않는 주 | 이번 주는 새로 알릴 내용이 없어요 |

> `done` 이어도 Gate 2 글은 **승인 주체 기관(O-40 `primarySource`)에만** 보입니다. 우리 기관이 승인 주체가 아니면
> 우리 Gate 2 목록에는 뜨지 않습니다. 실패 사유(`insight_run.failure_reason`)는 내부 메시지라 내려가지 않습니다.

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| 기관 소속이 아닌 사용자 | 403 | `ORGANIZATION_NOT_ASSIGNED` |

### 4.6 수신함 · 상담 지원 · 감사 로그

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-50 | GET | `/institutions/me/inbox` | 수신함 목록 · **구현됨** | I-12 |
| O-51 | GET | `/institutions/me/inbox/{insightId}` | 수신함 단건. 처음 열어 보면 열람 시각 기록 · **구현됨** | I-12 |
| O-52 | POST | `/children/{childId}/chat` | 상담 지원 질의 | I-13 |
| O-53 | GET | `/audit-logs` | 업무 기록 | I-14 |

> O-51 은 처음에 `POST /institutions/me/inbox/{id}/read`(읽음 처리)였습니다. **단건 조회가 대신합니다** (#273).
> 읽음 처리를 따로 두면 글을 열지 않고도 읽음을 찍을 수 있어, 열람 시각이 감사 기록으로서 거짓이 됩니다.

다른 기관이 Gate 2 에서 승인해 **우리 기관에 열린** 인사이트를 읽습니다 (`sharing_history`, 받는 쪽
`ORGANIZATION`). 승인한 기관 자신은 받지 않습니다. 보호자 인사이트(G-35 · G-36)와 같은 글입니다.

**O-50 응답** · 최근 열린 순, 페이지 없음. **본문(`content`)이 없습니다** — 단건(O-51)을 열어야 보이고, 그때
열람 시각이 남습니다. 같은 기간의 옛 회차도 숨기지 않습니다(둘 다 승인된 글).

```json
[ { "id": "12", "childId": "1", "childName": "김하늘", "externalId": "C-7",
    "from": { "id": "1", "name": "○○초등학교", "type": "school", "verified": false },
    "period": "2026년 8월 3주차",
    "receivedAt": "2026-08-21T15:20:00",
    "readAt": null } ]
```

**O-51 응답** · O-50 원소에 `content` 를 더한 모양입니다. 열면서 찍으므로 `readAt` 이 채워져 있습니다.

| 필드 | 뜻 |
| --- | --- |
| `id` | 인사이트 id. O-51 경로에 그대로 씁니다 |
| `externalId` | **우리 기관이 붙인** 관리번호. 이름이 같은 아이를 가릅니다 |
| `from` | 이 글을 Gate 2 에서 승인한 기관 |
| `receivedAt` | 우리 기관이 볼 수 있게 된 시각 |
| `readAt` | 우리 기관에서 처음 열어 본 시각. 아직 안 열었으면 **키가 있고 값이 `null`** 입니다. 기관 단위라 누가 열었는지는 남기지 않습니다 |
| `content` | (O-51 만) 인사이트 본문. **수신자별 변환은 아직 없고** 보호자에게 열린 글과 같습니다 |

> 같이 받은 다른 기관(`sharedWith`)은 **주지 않습니다.** 보호자와 달리 기관에게 "이 아이가 또 어디에
> 다니는지" 는 필요한 최소 정보를 넘습니다.

**읽을 때마다 다시 봅니다.** 목록과 단건이 같은 판정을 쓰고, 넷 다 참이어야 보입니다.

1. 권한이 거둬지지 않았다
2. 우리 기관 명부에 그 아이가 지금 있다
3. **우리 기관 기준** 동의가 켜져 있다 (아이 전체 `status` 가 아님)
4. 아이가 삭제되지 않았다

지금은 동의 철회·보호자 연결 해제가 권한을 함께 거두지만, 나중에 거두기를 빠뜨린 경로가 생겨도 새지
않게 합니다. 판정에 걸려도 권한을 거두지는 않습니다 — 조회는 권한을 바꾸지 않습니다.

**철회와 겹치면 먼저 반영된 쪽을 따릅니다.** 철회가 먼저면 404 이고 열람 시각이 남지 않습니다. 열람이
먼저면 200 과 열람 시각을 남긴 뒤 철회됩니다. 같은 글을 동시에 열면 처음 시각이 남고, 두 응답 모두 그
시각을 돌려줍니다 (`docs/db/schema.md` §9.4). G-36 도 같습니다.

**실패**

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| 기관 소속 사용자가 아님 | 403 | `ORGANIZATION_NOT_ASSIGNED` |
| 없는 글 · 다른 기관에 열린 글 · 위 판정에 걸린 글 (O-51) | 404 | `SHARING_NOT_FOUND` |

> 404 의 이유를 나누지 않습니다. 나누면 그 아이와 다른 기관 사이에 무슨 일이 있었는지가 샙니다.

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

### 4.7 준비물·확인사항

기관이 명부의 아이들에게 준비물 · 확인할 것을 보내고, 보호자가 확인했는지 봅니다(I-15). 보호자 쪽은 §5.6 입니다.
**공유 동의와 상관없습니다** — 동의는 아이 정보가 기관으로 가는 허락이고, 이 요청은 기관 → 보호자 방향입니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-70 | POST | `/institutions/me/checklists` | 보내기 · **구현됨** | I-15 |
| O-71 | GET | `/institutions/me/checklists[?before=]` | 보낸 목록, 아이별 확인 · **구현됨** | I-15 |
| O-72 | DELETE | `/institutions/me/checklists/{checklistId}` | 내리기 · **구현됨** | I-15 |

**O-70 요청 → 201** · 응답은 O-71 원소 하나입니다.

```json
{ "items": ["다음 주 현장학습 동의서를 확인해주세요.", "실내화를 새로 준비해주세요."],
  "childIds": ["1", "2"] }
```

| 규칙 | 내용 |
| --- | --- |
| 항목 | 1~10줄, 줄마다 1~100자. 앞뒤 공백은 떼고, 공백뿐인 줄은 400 |
| 받을 아이 | 1명 이상. 같은 id 는 하나로 합칩니다. **모두 우리 명부에 있어야** 합니다(삭제되지 않았고 명부 행이 살아 있음). 하나라도 아니면 아무것도 저장하지 않고 `400 INVALID_CHECKLIST_TARGET` — 어느 아이인지는 알려 주지 않습니다 |
| 보낸 순간의 명부 | "명부 전체" 도 화면이 그때의 id 를 모두 보냅니다. 나중에 들어온 아이는 받지 않습니다 |
| 고치지 않음 | 보낸 뒤에는 고칠 수 없고 O-72 로 내리기만 합니다 |

**O-71 응답** · 내리지 않은 요청 최신순 50개씩.

```json
{ "checklists": [
    { "id": "7", "items": ["실내화를 새로 준비해주세요."], "sentAt": "2026-11-04T09:10:00",
      "total": 2, "confirmed": 1,
      "targets": [
        { "child": { "id": "1", "name": "김하늘" }, "onRoster": true,
          "confirmedAt": "2026-11-04T19:02:00", "confirmedByName": "김보호" },
        { "child": { "id": "2", "name": "이바다" }, "onRoster": true,
          "confirmedAt": null, "confirmedByName": null } ] } ],
  "nextBefore": null }
```

| 필드 | 뜻 |
| --- | --- |
| `total` · `confirmed` | **지금 명부에 있는** 받은 아이 수와 그중 확인된 수. 명부에서 빠진 아이는 세지 않습니다 |
| `targets[].onRoster` | 아이가 삭제되지 않았고 지금도 우리 명부에 있음. `false` 면 "명부에서 빠짐" |
| `confirmedByName` | 확인한 보호자의 카카오 닉네임. 없으면 `null` |
| `nextBefore` | 더 오래된 요청이 있으면 그 값을 `?before=` 로 넣어 다음 50개를 봅니다. 기준 요청을 내렸어도 이어 볼 수 있습니다. 다른 기관 요청 id 를 넣으면 `400 INVALID_REQUEST` |

**O-72 → 204** · 받은 아이 모두에게서 통째로 내립니다. 이미 내렸으면 아무것도 바꾸지 않고 같은 204 입니다(처음 내린 시각이 남습니다).
없거나 다른 기관 요청이면 `404 CHECKLIST_NOT_FOUND`. 이미 남은 확인 기록은 지우지 않습니다. 보호자가 확인하는 중이면 그 확인이 끝난 뒤 내려집니다(§5.6).

### 4.8 대시보드 (#370)

기관 첫 화면(대시보드)이 쓰는 조회입니다. 대시보드는 10초마다 부릅니다 — 세 API 모두 요청마다 쿼리 수가 아이·일지 수와
상관없이 같습니다. 셋 다 기관 사용자만 볼 수 있고 아니면 `403 ORGANIZATION_NOT_ASSIGNED` 입니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-80 | GET | `/dashboard/pipeline` | 선생님 차례 건수와 진행 현황 줄 · **구현됨** | I-03 |
| O-81 | GET | `/dashboard/ai-activity` | 오늘 AI가 한 일 · **구현됨** | I-03 |
| O-82 | GET | `/system/ai-status` | AI 상태 (AI 장애 배너) · **구현됨** | 기관 화면 전체 |

**O-80 응답** · 단계 판정은 서버가 합니다. 화면은 받은 그대로 그리고, 여러 목록 API 를 맞춰 보지 않습니다.

```json
{ "turn": { "confirm": 1, "blocked": 1, "review": 2, "approve": 1, "requests": 0 },
  "rows": [
    { "kind": "entry", "childId": null, "childName": null, "date": "2026-11-05", "week": null,
      "step": 0, "who": "me", "status": "match_review", "detail": null,
      "target": { "type": "matching", "id": "12" } },
    { "kind": "entry", "childId": "8", "childName": "김하늘", "date": "2026-11-05", "week": null,
      "step": 1, "who": "me", "status": "blocked",
      "detail": { "kinds": ["진단명"], "reasons": ["진단명이 있어요 …"] },
      "target": { "type": "validation", "id": "30" } },
    { "kind": "week", "childId": "9", "childName": "이바다", "date": null,
      "week": { "from": "2026-10-26", "to": "2026-11-01", "label": "2026년 10월 4주차" },
      "step": 5, "who": "other", "status": "review_elsewhere",
      "detail": { "approverName": "해솔초등학교" }, "target": null } ] }
```

| 필드 | 뜻 |
| --- | --- |
| `turn` | 선생님 차례 5줄. `confirm` = 아이 확인(O-22 건수), `blocked` = 고쳐야 할 기록(O-24 건수), `review` = 검토 대기 요약(다시 만드는 중 제외) + 요약 실패 묶음, `approve` = 우리가 승인할 Gate 2 대기(O-40 `pending` 건수), `requests` = 보호자 연결 요청(O-60 건수) |
| `rows[].kind` | 검토 승인 전에는 `entry`(아이×날짜), 승인 뒤에는 `week`(아이×주, 월~일). 승인한 기록은 `entry` 에서 빠지고 그 주 줄로 모입니다 |
| `rows[].childId` · `childName` | 아이를 아직 못 찾은 줄은 `null` |
| `rows[].step` | 0 아이 매칭 · 1 내용 검증 · 2 요약 · 3 검토 · 4 주간 리포트 · 5 리포트 승인 (화면 이름은 FE 가 정하고, 서버는 번호만 줍니다) |
| `rows[].who` | `me` 선생님 차례 · `ai` AI 가 하는 중 · `wait` 기다리는 중 · `other` 다른 사람(관리자·다른 기관·보호자) 차례 · `done` 끝. 줄은 이 순서 → 단계 → 날짜(주 줄은 주 시작일) 순입니다 |
| `rows[].detail` | 상태마다 덧붙이는 값. 없는 키는 빠집니다 (`matching` → `count`, `blocked` → `kinds`·`reasons`, `review`·`review_held` → `noContent`, `review_elsewhere` → `approverName`) |
| `rows[].target` | 바로가기 대상. `matching`(O-28 항목 id) · `validation`(O-24 항목 id) · `summary`(요약 id) · `summary_failed`(`id` 없음 — 줄의 `childId`·`date` 로 O-35~37) · `insight`(인사이트 id). 선생님 차례가 아니면 `null` |

| step | status | who | 뜻 |
| --- | --- | --- | --- |
| 0 | `matching` | ai | 아이를 찾는 중. 아이 미정이라 **날짜마다 한 줄**, `detail.count` = 그날 일지 수 |
| 0 | `match_review` | me | AI 가 아이를 정하지 못함. 일지마다 한 줄 |
| 1 | `validating` | ai | 검증 대기·검증 중 |
| 1 | `blocked` | me | 검증에서 막힘. **일지마다 한 줄** — 같은 날 다른 일지는 본 줄로 계속 흘러갑니다 |
| 1·2 | `system_error` | other | 관리자 확인 대기 (#180). 묶음마다 한 줄, 실패한 단계(검증 1 · 요약 2)에 둡니다 |
| 2 | `waiting_same_date` | wait | 검증은 끝났지만 같은 기관·같은 날짜에 아직 매칭·검증 중인 일지가 있어 요약을 기다림 (#369) |
| 2 | `summarizing` | ai | 곧 요약하거나 요약 중 |
| 2 | `summary_failed` | me | 요약 실패 (O-35~37) |
| 3 | `review` · `review_held` | me | 검토 대기 · 보류해 둔 요약 |
| 3 | `regenerating` | ai | 새 기록이 들어와 요약을 다시 만드는 중 (O-30 `processing`) |
| 4 | `waiting_week_end` · `queued` · `running` · `retrying` | wait · ai · ai · ai | 주간 리포트 (O-43 과 같은 판정) |
| 4 | `not_consented` | other | 보호자가 주간 리포트 공유를 허락하지 않아 우리 기록은 쓰지 않음 |
| 4 | `nothing_new` | done | 이번 주는 새로 알릴 내용이 없음 (분석은 됐지만 우리 기록이 쓰인 글이 없는 주 포함) |
| 5 | `review_here` · `insight_held` | me | 우리가 승인 주체인 분석 — 승인 대기 · 보류 |
| 5 | `review_elsewhere` | other | 다른 기관이 승인할 차례. `detail.approverName` = 그 기관 이름. 승인 주체는 Gate 2 와 같습니다(학교 > 센터 > 활동지원사, 같은 종류면 기관 id 가 작은 곳) |
| 5 | `approved` | done | 모두 승인됨 |

- 아이를 아는 줄은 **지금 우리 명부에 있고 우리 기관 동의가 켜진 아이만** 나옵니다(O-34 와 같음). `match_review`·`blocked` 줄은
  그 큐(O-22·O-24)와 건수가 맞도록 거르지 않습니다.
- 검토 대기 요약이 있는 묶음에 새 일지가 들어오면 따로 줄을 만들지 않고 그 요약 줄이 `regenerating` 이 됩니다(다시
  요약이 실패한 새 일지만 `summary_failed` 줄로 따로 나옵니다). 보류한
  요약은 다시 요약하지 않으므로 새 일지는 따로 줄로 나옵니다.
- 주 줄은 **최근 14일 안에 검토를 승인한 주**만 나옵니다(O-43 과 같음). 그래서 `done` 줄도 최근 것만 남습니다.
- 자동 요약(#369)을 끈 비상 모드에서도 검증을 마친 묶음은 `summarizing` 으로 보입니다.
- O-43 은 기존 화면용으로 그대로 둡니다.

**O-81 응답** · 서울 기준 오늘 0시부터, 우리 기관 기록에 AI 가 한 일입니다. 선생님이 처리한 횟수는 넣지 않습니다.

```json
{ "split": 12, "matched": 7, "validated": 11, "summarized": 3, "insights": 1 }
```

| 필드 | 센 것 | 정확도 |
| --- | --- | --- |
| `split` | 오늘 나눠 만든 일지 수(지운 일지 포함) | 정확 |
| `matched` | 오늘 AI 가 그대로 확정한 매칭 수. 선생님이 확인 큐에서 고른 것은 뺍니다 | 정확 |
| `validated` | 오늘 남은 검증 결과 중 통과(`PASS`·`REVIEW`)와 막힘(`BLOCK`). AI 호출 실패는 뺍니다. 다시 검사하면 한 번 더 셉니다 | 정확 |
| `summarized` | 오늘 만든 요약 + 오늘 다시 만든 검토 대기 요약 | **근사** — 승인 전 요약은 다시 요약해도 같은 행을 덮어써서 수정 시각으로 셉니다. 보류를 풀어 검토 대기로 돌아온 요약도 섞일 수 있습니다 |
| `insights` | 오늘 만든 인사이트 중 지금 우리 명부 아이의, 우리 기관이 근거를 낸 것 | 정확 |

**O-82 응답** · AI 장애 배너용입니다.

```json
{ "available": false, "since": "2026-11-06T10:00:00+09:00" }
```

| 필드 | 뜻 |
| --- | --- |
| `available` | 워커가 일을 집기 전에 보는 AI `/health` 실패가 **15초 이상(3번 이상)** 이어지거나, 워커(매칭·검증·요약·인사이트)가 재시도(2초·5초) 끝에 AI 를 부르지 못해 멈추면 `false` 입니다. 워커 멈춤은 `/health` 가 LLM 까지 보지 않아 따로 알리며, 60초 동안은 `/health` 성공으로 풀지 않고 어느 워커든 AI 호출이 응답을 받으면 바로 풀립니다 |
| `since` | 이번 장애가 처음 보인 시각. `available` 이 `true` 면 `null` |

- 서버 메모리에만 두므로 서버가 다시 켜지면 처음부터 다시 셉니다. 서버가 여러 대가 되면 대마다 따로 판단합니다.

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
보호자: 링크 열기(코드를 sessionStorage에 보관) → 카카오 로그인 → 회원가입(§2.6) → 약관 동의(§2.5)
        → 코드 수락 (G-04) → 공유 범위 동의 (G-02 → G-41)
```

> **연결 완료 ≠ 동의 완료.** 수락(G-04)은 아동 상태를 바꾸지 않습니다. 응답의 `childStatus`는 아동의
> **실제 현재 상태**입니다 — 처음 연결이면 보통 `pending_consent`, 이미 다른 보호자가 동의해 둔 아이면 `active`입니다.

보호자가 **나중에 다른 기관을 추가로 연결**할 때는 기관 코드 입력(G-43 · G-44, §5.5)을
씁니다. 최초 온보딩 경로는 아니고, 설정 화면(P-13)에서만 쓰입니다. 바로 연결되지 않고 **기관이 수락(O-61)해야** 명부에 오릅니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-02 | GET | `/children/{childId}/consent-preview?institutionId=` | 아이 · 기관 · 공유 범위 미리보기 · **구현됨** | P-02 |
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
- 수락은 연결일 뿐 동의가 아니라 `childStatus`는 바뀌지 않습니다. 이어서 동의 미리보기(G-02) → 동의 저장(G-41)을 부릅니다.
- 연결되면 그 아이의 **이미 승인된 인사이트가 이 보호자에게도 열립니다**(G-35, #287). 엄마가 먼저 가입하고 아빠가
  나중에 연결돼도 같은 글을 봅니다. 끊었다 다시 연결하면 예전에 본 기록이 그대로라 이미 본 글은 새 글로 보이지 않습니다.

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

**G-02 응답** · `200 OK` · P-02 화면이 한 번에 그리는 정보입니다. 아무것도 저장하지 않습니다.

```json
{
  "child": { "id": "9", "name": "김하늘", "birthDate": "2017-03-14" },
  "institution": { "id": "1", "name": "○○초등학교",
                   "type": "school", "verified": false },
  "documentUrl": null,
  "allowedFields": ["daily_summary", "weekly_insight"]
}
```

| 필드 | 설명 |
| --- | --- |
| `child.id` | 동의(G-41)·연결 해제(G-05) 호출에 씁니다 |
| `institution.type` | `school` · `center` · `assistant` (DB의 `ACTIVITY_SUPPORT`가 `assistant`) |
| `institution.verified` | 언제나 `false`입니다. 사업자등록번호는 형식만 보고 진위는 확인하지 않습니다 |
| `documentUrl` | 증빙서류 저장 기능이 없어 언제나 `null`입니다. 프론트는 버튼을 숨깁니다 |
| `allowedFields` | **동의할 때 G-41에 그대로 돌려보낼 항목 코드**입니다. 실제로 저장·적용되는 것은 이 값입니다 |

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `institutionId` 누락·형식 오류 |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
| 403 | `PARENT_REQUIRED` | 보호자가 아닌 사용자(기관 담당자) |
| 403 | `FORBIDDEN` | 이 보호자와 연결되지 않은 아이 |
| 404 | `CONSENT_TARGET_NOT_FOUND` | 삭제된 아이, 아이가 명부에 없는 기관, **이미 이 기관에 동의가 있음**(다른 보호자의 동의 포함). 이유를 나누지 않습니다. 프론트는 이미 404를 "동의할 것 없음"으로 처리합니다. 철회된 뒤에는 다시 200입니다 |

### 5.2 아이 · 홈

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-10 | GET | `/guardians/me/children` | 연결된 아이 전체 (다자녀) · **구현됨** | 전 화면 |
| G-11 | GET | `/guardians/me/home?childId=` | 홈 데이터 · **구현됨** | P-03, P-04 |
| G-13 | PUT | `/children/{childId}/care-info` | 돌봄 정보(알레르기·복용약) 저장 · **구현됨** | P-06 |

> **G-12 오늘 요약(TODAY 카드)은 뺐습니다 (#280).** 오늘 승인된 일지를 보여 주면 바로 아래 "새 소식"(G-20)과 같은
> 글이 두 번 나오고, 교사는 보통 그날 늦게나 다음 날 승인해 낮에는 대부분 비어 있습니다. 번호는 결번으로 둡니다.

**G-10 응답** · `Child[]` (§3, `school` 없음. `care` 는 늘 실리고 저장 전이면 빈 배열)

```json
[
  { "id": "1", "name": "김하늘", "birthDate": "2017-03-14", "status": "active",
    "institutions": [
      { "institution": { "id": "2", "name": "햇살아동발달센터", "type": "center", "verified": false },
        "consent": "granted" },
      { "institution": { "id": "3", "name": "○○초등학교", "type": "school", "verified": false },
        "consent": "not_granted" }
    ],
    "linkRequests": [
      { "institution": { "id": "4", "name": "○○미술학원", "type": "center", "verified": false },
        "requestedAt": "2026-10-29T10:00:00+09:00" }
    ],
    "care": { "allergies": ["땅콩"], "medications": [{ "name": "○○정", "time": "점심 식후" }] } }
]
```

- **넣는 아이**: 이 보호자와 연결이 살아 있고 삭제되지 않은 아이 **전부**입니다. 동의 전(`pending_consent`)·동의를
  모두 끈(`suspended`) 아이도 들어갑니다. 설정 화면에서 기관을 다시 켜거나 연결을 끊으려면 보여야 해서입니다.
  연결된 아이가 없으면 `[]`입니다.
- **순서**: 연결된 순서입니다. 프론트가 "첫 번째 아이"를 고를 때 늘 같은 아이가 나옵니다.
- **`institutions`**: 아이가 지금 명부에 올라 있는 기관입니다. 기관 id 순입니다. 명부에서 빠진 기관은 없습니다.
- **`linkRequests`**: 기관 코드로 연결을 요청했고(G-44) 기관의 수락을 기다리는 기관입니다. 설정의 "수락 대기 중"에 씁니다.
  아직 명부에 없어 동의할 수 없으므로 `institutions`와 섞지 않습니다. 누구의 요청이든 아이의 보호자 모두에게 보이고,
  엄마·아빠가 같은 기관에 요청했어도 한 줄입니다(`requestedAt`은 가장 이른 요청). 수락되면 `institutions`로 옮겨 가고,
  거절되면 사라집니다. 없으면 `[]`.
- **`consent`는 아이 기준입니다.** 어느 보호자의 동의든 하나라도 살아 있으면 `granted`, 동의 기록은 있지만 모두
  꺼졌으면 `revoked`, 기록이 없으면 `not_granted`입니다. 엄마가 켠 동의는 아빠 화면에도 `granted`로 보입니다.
  동의 미리보기(G-02)·Gate 2 공유 대상(O-40)과 같은 계산입니다.

**G-11 요청** · `childId` **필수**

명세 초안의 "생략하면 첫 번째 아이"는 없앴습니다. 프론트는 G-10을 먼저 받아 지금 보고 있는 아이를 직접 고른 뒤
언제나 `childId`를 붙여 부릅니다. 서버는 그 아이를 볼 수 있는지만 확인합니다.

**G-11 응답** · `{ "child": Child, "activity": ParentActivity[] }`

`child`는 G-10의 한 항목과 같은 모양입니다. **`activity`는 승인된 일지(G-20) 줄과 받은 인사이트(G-35) 줄을
승인 시각 최근 순으로 섞은 5줄입니다** (#280, #289). 둘 다 없으면 `[]`입니다.

```json
{ "child": { "…": "Child 모델" },
  "activity": [
    { "id": "insight-2026-08-17-2", "kind": "insight", "at": "2026-08-24 15:30",
      "text": "○○초등학교에서 8월 3주차 리포트 2편이 도착했어요", "journalId": null, "insightId": null },
    { "id": "journal-3", "kind": "journal", "at": "2026-08-21 15:20",
      "text": "햇살아동발달센터에서 8월 21일 일지가 도착했어요", "journalId": "3", "insightId": null }
  ] }
```

| 필드 | 뜻 |
| --- | --- |
| `id` | 목록 안에서 겹치지 않는 값. 종류를 앞에 붙입니다(`journal-{일지 id}` · `insight-{기간 시작일}-{승인 기관 id}`). 화면은 목록 key 로만 씁니다 |
| `kind` | `journal` · `insight` |
| `at` | `yyyy-MM-dd HH:mm`. 일지는 교사가 Gate 1 에서 승인한 시각, 인사이트는 담당자가 Gate 2 에서 승인한 시각(G-35 `approvedAt`) — 보호자에게 도착한 때입니다. 일지 문구의 날짜는 관찰 날짜입니다 |
| `journalId` | 일지 줄이면 일지 상세(G-21)로 연결할 id |
| `insightId` | 인사이트 **한 편**이면 그 id(G-36). 여러 편을 묶은 줄이면 `null` — 프론트는 리포트 탭(G-35)으로 연결합니다 |

- 순서는 관찰 날짜가 아니라 승인 순입니다. 8월 기록을 10월에 승인하면 맨 위에 옵니다.
- **인사이트는 같은 기간·같은 승인 기관이면 한 줄로 묶습니다.** 한 번 분석에 여러 편이 나와 한 편씩 승인되는데, 같은
  문구가 줄줄이 서면 일지 소식이 밀려납니다. 묶음의 시각은 그중 가장 늦은 승인 시각입니다.
- **인사이트 줄은 이 보호자에게 지금 열린 이 아이의 글만입니다.** 연결 해제로 거둬진 글은 줄이 생기지 않아 눌러도
  404 가 없습니다. 승인 기관 행이 없는 묶음은 뺍니다(일지 줄과 같이 이름 모르는 기관을 올리지 않습니다).
- 2026-10-30 (#289) 이전에는 `id` 가 일지 id 그대로였고 `kind` · `insightId` 가 없었습니다.

**G-10 · G-11 오류**

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | (G-11) `childId` 누락·형식 오류 |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
| 403 | `PARENT_REQUIRED` | 보호자가 아닌 사용자(기관 담당자) |
| 403 | `FORBIDDEN` | (G-11) 이 보호자와 연결되지 않았거나 삭제된 아이. 이유를 나누지 않습니다 |

**G-13 요청** · 돌봄 정보 전체를 보냅니다 — **통째로 덮어씁니다(부분 수정 아님).** 비우는 것도 빈 배열로 덮어쓰는 것입니다.

```json
{ "allergies": ["우유와 유제품", "땅콩"],
  "medications": [{ "name": "○○정", "time": "점심 식후" }, { "name": "△△시럽" }] }
```

**G-13 응답** · 저장된 값(다듬은 뒤) `{ "allergies": [...], "medications": [{ "name", "time" }] }`. 조회는 따로 없고
아이 목록·홈(G-10 · G-11)의 `care` 로 합니다.

- **누가 보나**: 현재 이 아이와 연결된 모든 보호자가 조회·수정할 수 있고, **기관에는 공유하지 않습니다.** 아이마다 하나라
  엄마·아빠가 같은 값을 보고, 나중에 저장한 쪽이 이깁니다. 마지막에 고친 보호자와 시각을 남깁니다. 보호자 연결이 끊겨도 남습니다.
- **입력 규칙** (어기면 400 `INVALID_REQUEST`)
  - `allergies`·`medications` 둘 다 필수(`null` 안 됨). 각 최대 20개
  - 공백은 **앞뒤만** 뗍니다. 안쪽 공백은 그대로입니다(`"갑각류 (새우)"`). 공백뿐인 값은 400
  - 알레르기: 1–30자. **같은 값은 하나로 합칩니다**(처음 순서 유지). 20개는 **합친 뒤 개수**입니다 — 같은 값이 섞여 21개를 보내도 합쳐서 20개 이하면 통과
  - 복용약: 이름 1–30자, 복용 시간 0–30자. **복용 시간은 생략·`null` 가능**(빈 문자열로 저장)
- 동시에 저장해도 아이 행을 잠가 행이 하나만 생깁니다. 연결 해제와 겹치면 먼저 반영된 쪽을 따릅니다 — 해제가 먼저면 403 이고
  돌봄 정보는 생기지도 바뀌지도 않습니다 (`docs/db/schema.md` §3.3).
- 예전 초안의 **`welfareCard`(복지카드)와 `weeklySchedule`(주간 스케줄)은 뺐습니다(#285).** 복지카드(장애인 등록 여부)는
  민감정보(건강 정보)인데 쓰이는 곳이 없고, 주간 스케줄은 입력하는 곳이 없었습니다. 기관 공유나 지원 프로그램 안내가 생길 때
  별도 동의와 함께 다시 넣습니다. 보내도 무시합니다.

**G-13 오류**

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 위 입력 규칙 위반, JSON 형식 오류 |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
| 403 | `PARENT_REQUIRED` | 보호자가 아닌 사용자(기관 담당자) |
| 403 | `FORBIDDEN` | 이 보호자와 연결되지 않았거나 삭제된 아이(G-11 과 같음), CSRF 토큰 누락·불일치 |

### 5.3 일지

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-20 | GET | `/children/{childId}/journal` | 일지 목록 · **구현됨** | P-03, P-07 |
| G-21 | GET | `/journal/{id}` | 일지 상세 · **구현됨** | P-08 |

> **G-22 "이 내용이 이상해요"는 프로젝트에서 뺐습니다 (#280).** 신고를 받아 둘 곳도, 기관이 신고를 볼 곳도 없어
> 보호자에게 "전했습니다"라고만 하고 아무도 읽지 않게 됩니다. 번호는 결번으로 둡니다.

**일지 한 건 = 교사가 Gate 1 에서 승인한 요약 하나**(`child_context` 한 행)입니다. 사람이 승인한 글만 보호자에게 갑니다.

- 승인 뒤 같은 날 새 기록이 와서 새 판이 생기면 그 판도 따로 승인돼 **같은 날 같은 기관에 두 건**이 될 수 있습니다.
- "요약할 내용 없음"으로 확인만 한 날은 승인본이 없어 나오지 않습니다.
- **동의가 꺼졌거나 명부에서 빠진 기관의 지난 일지도 나옵니다.** 동의 철회는 다른 기관으로 가는 것을 막는 장치이지
  보호자가 자기 아이 이야기를 못 보게 하는 장치가 아닙니다 (받은 인사이트 G-35 와 같음).

**G-20 응답** · G-21 은 이 원소 하나와 같은 모양입니다.

```json
[
  { "id": "3", "childId": "1",
    "institution": { "id": "2", "name": "햇살아동발달센터", "type": "center", "verified": false },
    "date": "2026-08-21", "isNew": true,
    "content": "오후 미술 활동에 끝까지 참여했어요. …" }
]
```

| 필드 | 설명 |
| --- | --- |
| `id` | 승인본(`child_context`) id. 리포트의 근거 id(§5.4)도 이 값입니다 |
| `date` | 관찰 날짜. **시각은 없습니다** — 요약은 하루 단위입니다 |
| `isNew` | 승인된 지 **24시간 미만**이면 `true`(정각 24시간은 `false`). 보호자가 읽었는지와는 무관합니다 |
| `content` | 교사가 승인한 최종본. 고쳐서 승인했으면 고친 글입니다. 목록에서는 프론트가 2줄로 자릅니다 |

예전 초안의 `time`·`tag`·`summary`/`detail`·`institutionNote`·`photoCount`·`flagged`는 없습니다. 요약에 시각이 없고,
기록 종류 값 목록이 정해지지 않았고, 교사가 보호자에게 따로 쓰는 칸이 없고, 이미지 업로드는 이번 학기 범위 밖입니다.

- **정렬**: 관찰 날짜 최근 순, 같은 날은 승인 최근 순. 페이지 나누기는 없습니다.
- 기관 필터는 프론트에서 처리합니다. 명부에서 빠진 기관도 일지에 나오면 필터에 들어갑니다.
- 열람 기록은 남기지 않습니다(감사 로그 O-53 이 생길 때 함께).
- **G-21 과 연결 해제가 겹치면 먼저 반영된 쪽을 따릅니다** — 해제가 먼저면 404, 열람이 먼저면 200 뒤 해제
  (아이 행 공유 잠금, `docs/db/schema.md` §3.3). G-20 목록은 잠그지 않아 해제와 겹치면 진행 중이던 목록 한 번은 나갈 수 있습니다.

**G-20 · G-21 오류**

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
| 403 | `PARENT_REQUIRED` | 보호자가 아닌 사용자(기관 담당자) |
| 403 | `FORBIDDEN` | (G-20) 이 보호자와 연결되지 않았거나 삭제된 아이. 홈(G-11)과 같습니다 |
| 404 | `JOURNAL_NOT_FOUND` | (G-21) 없는 일지, 남의 아이 일지, 연결을 끊은 아이·삭제된 아이의 일지. 이유를 나누지 않습니다 — 그 id 의 일지가 있다는 사실도 알리지 않습니다 |

### 5.4 케어 리포트

> **2026-10-30 보류 (#289)** — 리포트 탭(P-09)은 받은 인사이트(G-35)를 씁니다. 주간·월간 그래프 리포트는 수치를
> 만들 곳이 없어 화면과 함께 지웠습니다. 아래는 예전 초안이라 남겨 둡니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-30 | GET | `/children/{childId}/care-report?period=weekly\|monthly` | 리포트 · **보류** | — |

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
G-20 의 `id` 는 승인본(`child_context`) id 입니다.

### 5.4-1 받은 인사이트

> **2026-10-26 신설 (#233)** — Gate 2 승인이 보호자에게 여는 인사이트를 보호자가 읽는 화면입니다.
> 보호자가 글마다 기관을 고르는 단계는 **없습니다** (10/26 기획 변경). 받는 기관은 보호자가 미리 해 둔
> 기관별 공유 동의로 정해집니다 (O-42).
>
> **2026-10-30 (#287)** — 나중에 연결된 보호자도 지난 승인 글을 봅니다(초대 수락 G-04 때 열림). 목록은 **승인 시각**
> 순이고 응답에 `approvedAt` 이 있습니다.
>
> **2026-11-07 (#405)** — 응답에 근거 일지 `evidence` 가 붙습니다. 보호자는 근거를 눌러 그 일지(G-21)로 갑니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-35 | GET | `/guardians/me/insights` | 내게 열린 인사이트 목록 | P-09, 셸(리포트 탭 점) |
| G-36 | GET | `/guardians/me/insights/{insightId}` | 단건. 처음 열어 보면 열람 시각 기록 | P-14 |

**G-35 응답** · **승인 시각(`approvedAt`) 최근 순**, 같으면 `id` 큰 순입니다. G-36 은 이 원소 하나와 같은 모양입니다.

```json
[
  { "id": "1", "childId": "1", "childName": "김하늘",
    "period": "2026년 8월 3주차",
    "content": "이동 전 5분 예고가 효과적이었습니다.",
    "from": { "id": "2", "name": "○○초등학교", "type": "school", "verified": false },
    "sharedWith": [
      { "id": "3", "name": "햇살아동발달센터", "type": "center", "verified": false }
    ],
    "approvedAt": "2026-08-24T15:30:00",
    "readAt": null,
    "evidence": [
      { "journalEntryId": "41", "date": "2026-08-18",
        "institution": { "id": "3", "name": "햇살아동발달센터", "type": "center", "verified": false },
        "lookedUp": false },
      { "journalEntryId": "44", "date": "2026-08-20",
        "institution": { "id": "2", "name": "○○초등학교", "type": "school", "verified": false },
        "lookedUp": false }
    ] }
]
```

| 필드 | 뜻 |
| --- | --- |
| `from` | 이 글을 Gate 2 에서 승인한 기관 |
| `approvedAt` | 담당자가 Gate 2 에서 승인한 시각. **모든 보호자에게 같습니다** — 늦게 연결돼 나중에 열린 글도 이 시각이라 엄마·아빠 화면에서 같은 자리·같은 날짜에 놓입니다. 기간(`period`)은 "어느 주의 이야기인지", 이 값은 "언제 받았는지" 입니다 |
| `sharedWith` | **지금 이 글을 볼 수 있는 다른 기관**, 기관 이름순. **기관 수신함(O-50 · O-51) · 공유 내역(G-45)과 같은 판정**입니다 — 권한이 살아 있고, 아이가 그 기관 명부에 있고, 그 기관 동의가 켜져 있어야 합니다(#319). 동의를 끄거나 명부에서 빠지면 빠집니다. 보호자가 "내 아이 글이 어디까지 갔나" 를 보고 동의를 끌지 판단하는 데 씁니다 |
| `readAt` | 처음 열어 본 시각. G-36 이 **처음 한 번만** 찍고 다시 열어도 덮지 않습니다. 동시에 열어도 처음 시각이 남습니다. 연결 해제와 겹치면 먼저 반영된 쪽을 따릅니다 — 해제가 먼저면 404, 열람이 먼저면 200 뒤 해제 (기관 수신함 O-51 과 같은 경로). 열람 방식이라 "언제 봤나" 를 안 남기면 아무 흔적이 없습니다 (`docs/db/schema.md` §9.4) |
| `evidence` | 이 글의 근거가 된 일지, **관찰 날짜 오래된 순**, 같은 날은 기관 이름순. 같은 일지는 한 번만 옵니다. `journalEntryId` 는 일지 상세(G-21)의 id, `date` 는 관찰 날짜(`yyyy-MM-dd`), `institution` 은 그 일지를 쓴 기관, `lookedUp` 은 이번 기간이 아니라 AI 가 지난 기록에서 찾아 쓴 근거인지입니다(Gate 2 근거의 `lookedUp` 과 같은 값, #387). 범위는 Gate 2 근거(O-40)와 같습니다 — 본문 근거(`claim_ids`)와, AI 가 찾아 본 지난 기록 중 본문이나 대처에서 실제로 인용한 것입니다. 그중 **응답 시점에 이 보호자가 일지 상세로 열 수 있는 것만** 옵니다 — 다른 아이 요약 · 1차 검토 전 요약 · 기관 행이 없는 요약은 빠지고, 아이가 삭제됐으면 빈 배열입니다. **동의로 거르지 않습니다.** 동의를 끈 기관 · 명부에서 빠진 기관의 일지도 일지 탭(G-20)에서 계속 보이므로 근거에도 남습니다. 남는 근거가 없으면 빈 배열이고 글은 그대로 보입니다 |

**실패**

| 실패 | 상태 | 코드 |
| --- | --- | --- |
| 보호자 계정이 아님 | 403 | `PARENT_REQUIRED` |
| 내게 열리지 않았거나 권한이 거둬진 인사이트 | 404 | `SHARING_NOT_FOUND` |

> **404 는 "없다" 가 아니라 "당신에게 안 열렸다" 는 뜻입니다.** 남의 아이 인사이트가 있는지조차
> 알려주지 않습니다. 연결이 끊긴 보호자도 그 뒤로는 404 입니다 (#214).

### 5.5 동의 · 기관 권한

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-40 | GET | `/children/{childId}/institution-requests/pending` | 대기 중 권한 요청 · **구현됨** | P-03, P-04, P-07, P-09, P-11 |
| G-41 | PUT | `/children/{childId}/consent-scopes/{institutionId}` | 동의 부여 / 회수 · **구현됨** | P-02, P-04, P-11 |
| G-42 | POST | `/children/{childId}/institution-requests/{institutionId}/decline` | 요청 거절 · **MVP 제외** | P-11 |
| G-43 | GET | `/institutions/lookup?code=` | 기관 코드 조회 · **구현됨** | P-13 |
| G-44 | POST | `/children/{childId}/institutions` | 기관 코드로 연결 요청 · **구현됨** | P-13 |
| G-45 | GET | `/guardians/me/children/{childId}/institution-shares` | 기관 공유 내역 — 이 아이 리포트가 어느 기관에 언제 열렸고 지금도 보이는지 · **구현됨** | P-04, P-05 |

**G-40** — 프론트 **알림 배지의 단일 기준점**입니다. 홈 · 타임라인 · 리포트 · 설정
헤더의 빨간 점이 전부 이 엔드포인트 하나를 봅니다.

조건: 해당 아이에 연결됐고 `consent === "not_granted"`이며, 보호자가 **거절하지 않은**
기관.

```json
[ { "institution": { "id": "4", "name": "○○미술학원",
                     "type": "center", "verified": false } } ]
```

> **현재 구현** (#232)
> - 아이가 **지금 명부에 올라 있고 아직 아무도 동의하지 않은**(`not_granted`) 기관입니다. 기관 id 순입니다.
> - `consent`는 아이 목록(G-10)과 같은 **아이 기준**입니다. 다른 보호자가 동의한 기관은 빠집니다.
> - 동의했다가 꺼진(`revoked`) 기관도 빠집니다. 보호자가 직접 끈 것이라 다시 알리지 않습니다. 설정에서 켤 수 있습니다.
> - 거절(G-42)이 없어 "거절하지 않은" 조건은 아직 없습니다.
>
> | 상태 | `code` | 상황 |
> | --- | --- | --- |
> | 401 | `UNAUTHORIZED` | 로그인하지 않음 |
> | 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
> | 403 | `PARENT_REQUIRED` | 보호자가 아닌 사용자(기관 담당자) |
> | 403 | `FORBIDDEN` | 이 보호자와 연결되지 않았거나 삭제된 아이 |

**G-41 요청**

```json
{ "action": "grant", "allowedFields": ["daily_summary", "weekly_insight"] }
```

| 필드 | 설명 |
| --- | --- |
| `action` | `grant` \| `revoke`. 필수 |
| `allowedFields` | 동의 항목 코드 `daily_summary` · `weekly_insight`. **`grant`에서 필수(1개 이상)**, `revoke`에서는 생략해도 되고 보내도 무시합니다. 같은 코드가 두 번 오면 하나로 합칩니다. **`weekly_insight` 가 없으면 그 기관은 주간 인사이트를 받지 않고, 이미 받은 인사이트 권한도 거둬집니다** (#257) |

**G-41 응답** · `200 OK` · 바뀐 뒤의 상태를 돌려줍니다. 아이 목록을 다시 부르지 않고 화면을 고칠 수 있습니다.

```json
{ "childStatus": "active", "consent": "granted", "allowedFields": ["daily_summary", "weekly_insight"] }
```

| 필드 | 설명 |
| --- | --- |
| `childStatus` | 아이 전체 요약 상태(§4.2 O-11 참고). 어느 기관이든 동의가 있으면 `active`, 모두 철회됐으면 `suspended` |
| `consent` | **이 기관**에 대한 동의 상태 `granted` · `revoked` · `not_granted` |
| `allowedFields` | 이 기관에 지금 공유되는 항목. `granted`가 아니면 빈 배열 |

**규칙**

- **보호자 한 명만 동의해도** 그 기관 공유가 켜집니다.
- **공유 항목은 기관 단위로 하나입니다.** 마지막으로 동의한 보호자의 `allowedFields`가 그 기관 전체에 적용됩니다
  (엄마가 두 항목에 동의한 뒤 아빠가 한 항목으로 동의하면 그 기관은 한 항목만 공유됩니다).
- **보호자 한 명이 `revoke`하면 그 기관에 대한 모든 보호자의 동의가 꺼집니다.** 끄는 쪽을 우선합니다.
  다른 보호자가 다시 `grant`하면 다시 켜집니다.
- 같은 요청을 여러 번 보내도 결과가 같습니다. 끌 동의가 없는 `revoke`는 아무것도 바꾸지 않고 `200`과 현재 상태를 돌려줍니다.
- `revoke`는 그 기관이 **받은** 인사이트 열람권과, 그 기관 기록이 **재료인** 인사이트를 다른 기관이 받은 열람권을 거둡니다(DB 스키마 §9.4).
  기관이 이미 쓴 기록 · 승인한 요약과 보호자 열람권은 그대로입니다. 다시 `grant`해도 거둔 열람권은 되살리지 않습니다.
  프론트는 끄기 확인 시트에 남는 것과 거둬지는 것을 구분해 표시합니다(#310).
- 보호자 연결이 끊기면(G-05·O-19) 그 보호자의 동의는 모든 기관에서 자동으로 꺼집니다. 다른 보호자의 동의는 그대로입니다.

> **화면 안내 필요.** 위 규칙 중 "한 보호자의 철회가 다른 보호자의 동의까지 끈다", "마지막 동의의 항목이 기관 전체에
> 적용된다"는 사용자가 예상하기 어렵습니다. 동의 화면(P-02)과 회수 확인 시트(P-04·P-11)에 안내해 주세요.

> **이번 범위의 한계 (#184).** 철회하면 그 기관 매칭 명단에서 바로 빠지고 새 매칭 확정도 막히지만,
> **철회 전에 이미 매칭돼 AI 처리 중인 기록**은 검증·요약까지 계속 진행됩니다. 이 차단은 후속 작업입니다.

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `action` 누락·모르는 값. `grant`의 `allowedFields` 누락·빈 배열·모르는 코드·`null` 항목(`["daily_summary", null]`) |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` | 회원가입을 끝내지 않음 |
| 403 | `PARENT_REQUIRED` | 보호자가 아닌 사용자(기관 담당자) |
| 403 | `FORBIDDEN` | 이 보호자와 연결되지 않은 아이, CSRF 토큰 누락·불일치 |
| 404 | `CONSENT_TARGET_NOT_FOUND` | 삭제된 아이, 아이가 명부에 없는 기관. 동의할 수 있는 기관은 아이가 지금 명부에 올라 있는 기관뿐입니다 |

**G-42** — 거절은 `consent`를 바꾸지 않습니다(`not_granted` 유지). 기관이 다시 요청할
수 있어야 하므로, **알림 목록에서만 제외**되도록 "이 보호자가 이 기관 요청을 거절함"을
따로 기록해주세요.

> **MVP 제외** (#232) · 거절 기록 테이블이 필요해 이번에는 만들지 않았습니다. 프론트 알림 화면에도 거절 버튼이
> 없습니다. 동의하지 않을 기관의 알림 점은 남지만, 보호자는 설정에서 언제든 동의를 켤 수 있습니다.
> 붙일 때는 G-40 조건에 "이 보호자가 거절하지 않은"을 더하고, 보호자별 거절·연결 해제 때 정리·기관의 다시 요청
> 방법을 함께 정합니다.

**G-43 응답** · `Institution`(§3). **조회만 하고 연결하지 않습니다.** 보호자가 기관을 확인한 뒤 G-44로 요청합니다.
`code`의 공백·하이픈·대소문자는 무시합니다.

> **현재 구현** (#258) · 일치하는 기관이 없으면 **`404 INSTITUTION_CODE_NOT_FOUND`** 입니다. 초안의 "`data: null`"은 공통 응답이
> `null` 필드를 빼서 그대로 나갈 수 없어 바꿨습니다. 프론트 `lookupInstitutionByCode`가 404를 `null`로 바꿔 씁니다.
> 형식이 틀린 코드와 없는 코드는 나누지 않습니다.

**G-44 요청** · `{ "code": "기관 코드" }`

**G-44 응답** · `200 OK`

```json
{ "institution": { "id": "4", "name": "○○미술학원", "type": "center", "verified": false },
  "status": "pending", "requestedAt": "2026-10-29T10:00:00+09:00" }
```

> **현재 구현** (#258) · **바로 연결하지 않고 연결 요청을 만듭니다.** 기관이 관리번호를 넣어 수락(O-61)하면 아이가 그 기관 명부에
> 오르고, 그때 그 기관이 "새 기관 권한 요청"(G-40)으로 뜹니다. 보호자는 거기서 공유에 동의(G-41)합니다.
> - 같은 보호자가 다시 요청하면 새로 만들지 않고 기다리는 요청을 돌려줍니다(`requestedAt` 이 같습니다).
> - 다른 보호자의 요청과는 따로 남습니다. 먼저 요청한 보호자가 아이 연결을 끊어도(G-05·O-19) 이 보호자의 요청은 남습니다 —
>   끊긴 보호자의 요청만 거둡니다.
> - 기관이 거절(O-62)해도 알리지 않습니다. 아이 응답의 `linkRequests`에서 사라지고, 다시 요청할 수 있습니다.
>
> | 상태 | `code` | 상황 |
> | --- | --- | --- |
> | 400 | `INVALID_REQUEST` | `code` 누락·공백 |
> | 401 | `UNAUTHORIZED` | 로그인하지 않음 |
> | 403 | `SIGNUP_NOT_COMPLETED` · `PARENT_REQUIRED` | 가입 미완료 · 보호자가 아닌 사용자 |
> | 403 | `FORBIDDEN` | 이 보호자와 연결되지 않았거나 삭제된 아이, CSRF 토큰 누락·불일치 |
> | 404 | `INSTITUTION_CODE_NOT_FOUND` | 일치하는 기관 없음 |
> | 409 | `INSTITUTION_ALREADY_LINKED` | 아이가 이미 그 기관 명부에 있음 |

**G-45 응답** · `200 OK` · 이 아이의 주간 리포트가 받는 기관에 열린 내역을 **기관별로** 줍니다(#317). 기관은 이름순, 묶음 안은 열린
순서 최신부터입니다. **거둔(가려진) 공유도 옵니다** — 동의 화면 · 끄기 확인창이 "서로 봐요", "끄면 가려져요" 라고 한 것을 보호자가
확인하는 자리입니다. 지금 명부에서 빠진 기관도 받은 이력이 있으면 옵니다.

```json
[
  { "institution": { "id": "3", "name": "○○초등학교", "type": "school", "verified": false },
    "shares": [
      { "insightId": "12", "period": "2026년 10월 4주차",
        "from": { "id": "1", "name": "햇살센터", "type": "center", "verified": false },
        "grantedAt": "2026-10-29T17:40:00", "visible": false, "hiddenAt": "2026-11-02T09:30:00" }
    ] }
]
```

| 필드 | 설명 |
| --- | --- |
| `institution` | 리포트를 받은 기관 |
| `shares[].from` | 이 리포트를 Gate 2 에서 승인한 기관. 없어진 기관이면 `null` |
| `shares[].period` | 리포트 목록(G-35)과 같은 기간 문구 |
| `shares[].grantedAt` | 그 기관에 열린 시각 |
| `shares[].visible` | 그 기관이 **지금** 볼 수 있는지. **기관 수신함(O-50 · O-51)과 같은 판정**입니다 — 권한이 살아 있고, 아이가 그 기관 명부에 있고, 그 기관 동의가 켜져 있어야 `true` |
| `shares[].hiddenAt` | 가려진 시각(권한을 거둔 시각). 볼 수 있거나, 거둔 기록 없이 명부 · 동의로 가려졌으면 `null` |

- 보호자 열람 줄(`GUARDIAN`)은 오지 않습니다. 기관에 열린 것만입니다.
- **가려진 이유는 주지 않습니다.** 공유 끔 · 연결 해제 · 명부 제외를 나누면 다른 보호자의 연결 해제 같은 일이 드러납니다.
- 기관이 열어 봤는지(열람 시각)도 주지 않습니다. 보여 줄지는 아직 정하지 않았습니다.

| 상태 | `code` | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | `childId` 형식 오류 |
| 401 | `UNAUTHORIZED` | 로그인하지 않음 |
| 403 | `SIGNUP_NOT_COMPLETED` · `PARENT_REQUIRED` | 가입 미완료 · 보호자가 아닌 사용자 |
| 403 | `FORBIDDEN` | 이 보호자와 연결되지 않았거나 삭제된 아이. 보호자 연결이 살아 있어도 아이가 삭제됐으면 막습니다 |

### 5.6 준비물·확인사항

기관이 보낸 준비물 · 확인할 것을 보고 확인합니다(P-12). 기관 쪽은 §4.7 입니다. 알림 벨의 "기관 권한 요청"(G-40, `/institution-requests/pending`)과는 다른 기능입니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-50 | GET | `/children/{childId}/checklists[?status=needs_check]` | 받은 목록 · **구현됨** | P-04, P-11, P-12, 헤더 알림 점 |
| G-51 | POST | `/children/{childId}/checklists/{checklistId}/confirm` | 확인 · **구현됨** | P-12 |

**G-50 응답** · G-51 응답은 이 원소 하나입니다.

```json
[ { "id": "7",
    "institution": { "id": "1", "name": "○○초등학교", "type": "school", "verified": false },
    "items": ["실내화를 새로 준비해주세요."],
    "sentAt": "2026-11-04T09:10:00",
    "status": "needs_check",
    "confirmedAt": null,
    "confirmedByName": null } ]
```

| 규칙 | 내용 |
| --- | --- |
| 누가 | 이 아이와 연결된 보호자. 연결되지 않았거나 아이가 삭제됐으면 `403 FORBIDDEN` (목록 · 확인 모두) |
| 보이는 요청 | 기관이 내리지 않았고 아이가 보낸 기관 명부에 **지금** 있는 것. 보낸 뒤에 연결된 보호자도 봅니다 |
| 순서 · 개수 | **확인 안 한 것 전부**(최신순) 뒤에 **확인한 것 최근 20개**(최신순). 확인 안 한 것은 잘리지 않아 개수가 곧 전체 수입니다 |
| `status=needs_check` | 확인 안 한 것만. 헤더 알림 점 · 설정 "N건 남음" · 알림 화면이 화면마다 부릅니다 |
| 확인 | 이 아이 몫으로 한 번. 보호자 중 한 명이 확인하면 모두에게 `confirmed` 이고 되돌리지 않습니다. 이미 다른 보호자가 확인했으면 그 확인(`confirmedByName` · `confirmedAt`)을 그대로 200 으로 돌려줍니다 |
| 내리기 · 연결 해제와 겹치면 | 줄을 섭니다. 기관이 먼저 내렸으면 `404 CHECKLIST_NOT_FOUND`, 보호자 연결이 먼저 끊겼으면 `403 FORBIDDEN` 이고 확인은 남지 않습니다. 확인이 먼저면 확인이 남고 내리기 · 해제는 그 뒤에 진행됩니다 |
| 404 | 이 아이가 받지 않았거나, 기관이 내렸거나, 명부에서 빠진 요청은 모두 같은 `404 CHECKLIST_NOT_FOUND` 입니다. 화면은 원인을 단정하지 않고 "이 요청을 더 이상 확인할 수 없어요" 뒤 목록을 다시 읽습니다 |

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
| `TERMS_NOT_AGREED` | 403 | 자기 역할의 약관에 동의하지 않은 회원(보호자 · 기관 담당자)이 `/auth/me` · `/auth/terms` · `/auth/logout` · 약관 본문 외 API 호출 → 그 역할의 약관 화면으로 이동 (§2.5) |
| `TERMS_VERSION_CHANGED` | 409 | 동의 요청의 `versions` 가 지금 판과 다름 — 화면을 연 뒤 약관이 바뀜 → 약관을 다시 받아 다시 확인 (§2.5) |
| `INSTITUTION_CODE_NOT_FOUND` | 404 | "일치하는 기관을 찾지 못했어요. 코드를 다시 확인해주세요." (G-43 조회 · G-44 요청) |
| `INSTITUTION_ALREADY_LINKED` | 409 | "이미 연결된 기관이에요." (G-44 요청 · O-61 수락) |
| `LINK_REQUEST_NOT_FOUND` | 404 | 기다리는 연결 요청이 없는 아이. 목록을 다시 불러옵니다 (O-61 · O-62) |
| `INSTITUTION_CODE_ISSUE_FAILED` | 503 | "기관 코드를 만들지 못했어요. 잠시 후 다시 시도해주세요." (O-01, 기관 회원가입) |
| `INVALID_CHECKLIST_TARGET` | 400 | 받을 아이 중 우리 명부에 없는 아이가 있음 — "명부에 없는 아이가 있어요" 후 명부를 다시 읽음 (O-70) |
| `CHECKLIST_NOT_FOUND` | 404 | 없거나 다른 기관 · 내린 · 명부에서 빠진 준비물·확인사항 (O-72 · G-51) — "이 요청을 더 이상 확인할 수 없어요" |
| `PARENT_REQUIRED` | 403 | 보호자가 아닌 회원(기관 담당자)이 보호자 전용 API 호출 → "보호자만 이용할 수 있어요" |
| `INVALID_INVITE_CODE` | 400 | "초대 코드가 올바르지 않거나 만료되었어요." 기관에 새 코드를 요청하도록 안내 (G-04) |
| `LINK_ALREADY_EXISTS` | 409 | "이미 연결된 아이입니다" (G-04) |
| `INVITATION_NOT_FOUND` | 404 | 없거나 이미 쓸 수 없는 초대 코드 (O-17) |
| `GUARDIAN_NOT_FOUND` | 404 | 이 아이와 연결되지 않은 보호자 (O-19) |
| `GUARDIAN_LINKED_BY_OTHER_ORGANIZATION` | 403 | "다른 기관을 통해 연결된 보호자는 해제할 수 없습니다" (O-19) |
| `INVITE_CODE_ISSUE_FAILED` | 503 | "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해주세요." (O-11·O-15) |
| `CONSENT_TARGET_NOT_FOUND` | 404 | 동의할 기관이 없음 — 삭제된 아이, 명부에 없는 기관, (G-02) 이미 동의한 기관. G-02는 프론트가 "동의할 것 없음"으로 처리 (G-02·G-41) |
| `CONSENT_NOT_GRANTED` | 403 | Gate 2에서 미동의 기관 선택 시 |
| `CHILD_CONSENT_PENDING` | 409 | "보호자 동의가 완료되기 전에는 기록을 올릴 수 없습니다" |

### 기록 · 검토

| 코드 | 상태 | 설명 |
| --- | --- | --- |
| `FILE_TOO_LARGE` | 413 | 업로드 용량 초과 (현재 구현: `RAW_RECORD_FILE_TOO_LARGE`) |
| `RAW_RECORD_ENTRY_LIMIT_EXCEEDED` | 400 | 파일에서 나온 기록이 500건을 넘거나 기록 하나가 5,000자를 넘음 — 파일을 저장하지 않음 (O-20, #424) |
| `RAW_RECORD_UPLOAD_BUSY` | 429 | 서버 전체의 동시 업로드 처리 자리와 대기가 꽉 참 — 파일을 저장하지 않음 (O-20, #424) |
| `AGENT_QUEUE_FULL` | 429 | 그 기관 AI 처리 줄이 꽉 참 (O-20 · O-23 `assign` · O-25 `revise` · O-31 `revert` · O-36, #424) |
| `UNSUPPORTED_FILE_TYPE` | 400 | 허용되지 않은 확장자 (현재 구현: 입력 오류와 함께 `RAW_RECORD_INVALID_REQUEST`) |
| `CHILD_NOT_FOUND` | 404 | 없는 아이 |
| `RECORD_NOT_FOUND` | 404 | 없는 기록 (현재 구현: `RAW_RECORD_NOT_FOUND`) |
| `MATCHING_RESULT_INVALID_REQUEST` | 400 | 확인 필요 큐 처리 요청 거절 (O-23) |
| `MATCHING_RESULT_NOT_FOUND` | 404 | 없거나 다른 기관의 확인 필요 큐 항목 (O-23) |
| `SUMMARY_INVALID_REQUEST` | 400 | 요약할 기록이 없어 요약 요청 거절 (O-32), 지원하지 않는 `gate1Status` (O-30), 문장 카드가 원래 문장과 맞지 않음 (O-31) |
| `SUMMARY_CHILD_NOT_FOUND` | 404 | 없거나 다른 기관의 아이로 요약 요청 (O-32), 우리 기관 동의가 없는 아이의 요약 실패 처리 (O-36 · O-37) |
| `SUMMARY_NOT_FOUND` | 404 | 없거나 다른 기관의 요약 (O-31 · O-33) |
| `SUMMARY_ALREADY_REVIEWED` | 409 | 이미 처리된 요약 재결정 (O-31) |
| `SUMMARY_IN_PROGRESS` | 409 | 요약하는 중인 묶음에 다시 요약 요청 (O-32) |
| `SUMMARY_REGENERATING` | 409 | 새 기록을 반영해 요약을 다시 만드는 중 (O-31 · O-33) |
| `SUMMARY_CHANGED` | 409 | 목록에서 본 뒤 요약이 새 기록으로 바뀜 (O-31) |
| `SUMMARY_HELD` | 409 | 보류된 요약을 승인·보류 — 보류 해제 먼저 (O-31) |
| `SUMMARY_NOT_HELD` | 409 | 보류되지 않은 요약 보류 해제 (O-33) |
| `SUMMARY_FAILED_NOT_FOUND` | 404 | 그 묶음에 요약 실패 기록이 없음 — 이미 처리했거나 다른 기관 기록 (O-36 · O-37) |
| `INSIGHT_INVALID_REQUEST` | 400 | 지원하지 않는 `gate2Status` (O-40) |
| `INSIGHT_NOT_FOUND` | 404 | 없는 인사이트이거나 근거를 내지 않은 기관 (O-41 · O-42) |
| `INSIGHT_NOT_PRIMARY_SOURCE` | 403 | 근거는 냈지만 승인 주체가 아닌 기관의 Gate 2 시도 (O-42) |
| `INSIGHT_ALREADY_REVIEWED` | 409 | 이미 승인·보류된 Insight 재결정 (O-42) |
| `INSIGHT_MATERIAL_CONSENT_REVOKED` | 409 | 재료 기관의 공유 동의가 철회된 Insight 승인 (O-42) |
| `JOURNAL_NOT_FOUND` | 404 | 보호자가 볼 수 없는 일지 — 없음·남의 아이·끊긴 연결·삭제된 아이 (G-21) |
| `SHARING_NOT_FOUND` | 404 | 보호자·우리 기관에 열리지 않았거나 지금 볼 수 없는 Insight 조회 (G-36, O-51) |

---

## 7. 구현 우선순위 제안

프론트가 화면별로 바로 붙일 수 있는 순서입니다.

| 순위 | 범위 | 엔드포인트 | 붙는 화면 |
| --- | --- | --- | --- |
| 1 | 인증 · 세션 | `/oauth2/authorization/kakao`(완료), `/auth/logout`(완료), `/auth/me`(완료), `/auth/signup`(완료), `/auth/terms`(완료), `/terms`(약관 본문, 완료) | I-01, P-01, 전 화면 가드 |
| 2 | 아동 조회 | O-10, O-14 (O-13 은 만들지 않음) | I-09, I-09-1 |
| 3 | 기록 등록 · 큐 | O-20 ~ O-25 | I-05, I-06, I-07 |
| 4 | Gate 1 | O-30, O-31, O-33 | I-08, I-03 |
| 5 | 보호자 연결 · 동의 | O-15 ~ O-19, G-04 · G-05, G-02, G-41 (모두 완료) | I-04, I-09-1, P-01, P-02 |
| 6 | 보호자 열람 | G-10 · G-11 · G-13 · G-20 · G-21 (G-12 · G-22 는 뺌) | P-03, P-07, P-08 |
| 7 | Insight · Gate 2 | O-40 ~ O-42 | I-10, I-11 |
| 8 | 동의 관리 · 알림 | G-40, G-42 ~ G-51(G-50 · G-51 완료), O-70 ~ O-72(완료) | P-04, P-11, P-12, P-13, I-15 |
| 9 | 수신함 · 리포트 · 상담 | O-50 ~ O-53, G-30 | I-12, I-13, I-14, P-09 |

각 단계가 끝나면 프론트는 `lib/api.ts`의 해당 함수 본문만 교체하면 되므로,
**엔드포인트 단위로 순차 연동이 가능합니다.** 전부 완성될 때까지 기다릴 필요는
없습니다 (`VITE_USE_MOCK`으로 섞어 쓸 수 있도록 함수별 스위치도 검토 중입니다).
