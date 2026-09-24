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
| 1 | **카카오 로그인 설정** | 인증은 **카카오 로그인으로 통일**하기로 했습니다 (`feat/be/#3` 방향과 동일). | 인가 코드 교환 위치(프론트 → BE 전달 vs BE가 리다이렉트 직접 수신), 리다이렉트 URI(로컬·배포), 카카오 동의 항목(닉네임 · 전화번호 등) 회신 필요. 기관 로그인(I-01)과 보호자 진입(P-01) 화면은 프론트가 재작업합니다 |
| 2 | **세션 전달 방식** | `feat/be/#3`은 `{ accessToken }` 반환 | **httpOnly 쿠키 선호.** 브라우저 JS가 토큰을 보관하지 않으면 XSS로 토큰이 새지 않습니다. Authorization 헤더로 간다면 저장 위치·만료·갱신 정책을 함께 정해주세요 |
| 3 | **필드 네이밍** | 프론트 타입은 camelCase, 일부 요청 바디는 snake_case | **camelCase 통일 제안** (Jackson 기본값과도 맞음). 이 문서는 전부 camelCase로 적었습니다. 프론트의 snake_case 요청 바디 3곳은 프론트가 수정합니다 |
| 4 | **보호자–아이 연결 방식** | **확정** — 기관이 먼저 아이를 등록해두고, 보호자가 카카오 로그인하면 **대기 중 연결 요청**으로 노출합니다. 초대코드 방식은 제외 | 남은 확정 사항은 **매칭 키**입니다(§5.1). 기관이 등록한 아이와 카카오 로그인한 보호자를 서버가 무엇으로 이어줄지 정해야 합니다 |
| 4-1 | **역할 구분** | 미정 | 카카오 계정 하나로 기관 담당자(`ORGANIZATION`)와 보호자(`PARENT`)를 어떻게 구분할지. 가입 시 선택 vs 기관 측 승인 |
| 5 | **파이프라인 진행 상태** | 프론트는 `setTimeout` 시뮬레이션 | 폴링 / SSE / WebSocket 중 선택. 폴링이면 권장 주기와 상태 조회 엔드포인트가 필요합니다 |
| 6 | **페이지네이션** | 미정 | 현재 프론트는 전체 조회를 가정합니다. 큐·타임라인·일지 목록에 커서 또는 오프셋 페이징이 필요하면 형식을 정해주세요 |
| 7 | **파일 업로드** | `feat/be/#4`는 `POST /api/raw-records` multipart | 다중 파일 업로드 지원 여부, 용량 제한(413 기준), 허용 확장자 회신 필요 |
| 8 | **RawRecord 응답 형식** | `feat/be/#4` 응답이 기획서 11.1과 다름 | 프론트 `RawRecord` 타입(§3.2)과 매핑표 합의 필요 |

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
- 보호 API는 인증 실패 시 `401 UNAUTHORIZED`, 역할 불일치 시 `403 FORBIDDEN`
- 역할: `PARENT` · `ORGANIZATION` · `ADMIN`

### 2.2 세션 조회 — 라우트 가드가 매 진입마다 호출

```http
GET /api/v1/auth/me
```

```json
{
  "result": "SUCCESS",
  "data": {
    "role": "org",
    "userId": "u_1",
    "name": "박지현",
    "institutionId": "inst_center_1"
  }
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `role` | `"org"` \| `"parent"` \| `"admin"` | 프론트 `Role` 타입과 매핑 |
| `institutionId` | string | `role === "org"`일 때만 |

비로그인 요청에는 `401`을 반환해주세요. 프론트는 `401`을 "역할 없음"으로 해석해
로그인 화면으로 보냅니다.

### 2.3 로그아웃

```http
POST /api/v1/auth/logout   → 204 No Content
```

### 2.4 로그인 — 카카오

기관 담당자와 보호자 **모두 카카오 로그인**을 사용합니다.

```http
POST /api/v1/auth/kakao
{ "code": "카카오 인가 코드", "redirectUri": "https://itda.app/auth/kakao/callback" }
```

**응답** · 세션 쿠키를 심고, 바디에는 아래만 주는 형태를 선호합니다(§1-2 참고).

```json
{
  "result": "SUCCESS",
  "data": {
    "userId": "u_9",
    "role": "parent",
    "name": "박지현",
    "isNewUser": true,
    "termsAgreed": false
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `role` | 역할이 아직 정해지지 않은 신규 가입자는 `null`. 프론트는 역할 선택 화면으로 보냅니다 |
| `isNewUser` | `true`면 약관 동의 → 기관 연결 단계로 진입 |
| `termsAgreed` | 서비스 자체 약관 동의 여부 |

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

---

## 3. 공통 모델

응답 `data` 안에서 반복되는 객체입니다. 프론트 타입 정의는
[feature-interfaces.md](../frontend/feature-interfaces.md) §2와 동일합니다.

### Institution

```json
{ "id": "inst_center_1", "name": "햇살아동발달센터", "type": "center", "verified": true }
```

`type`: `school` · `center` · `assistant`

### Child

```json
{
  "id": "child_1",
  "name": "김하늘",
  "birthDate": "2017-03-14",
  "school": "○○초 2학년",
  "status": "active",
  "institutions": [
    { "institution": { "id": "inst_school_1", "name": "○○초등학교", "type": "school", "verified": true },
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
  "id": "raw_1",
  "fileName": "0821_관찰일지.docx",
  "type": "관찰일지",
  "capturedAt": "2026-08-21T11:40:00+09:00",
  "preview": "오늘 오전 활동 중…"
}
```

`type`: `관찰일지` · `활동일지` · `특이사항` · `사진`
(한국어 고정값을 그대로 쓸지, enum 코드값 + 표시명 분리로 갈지 의견 주세요.)

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
| O-10 | GET | `/institutions/me/children` | 담당 아동 목록 | I-05, I-09, I-13 |
| O-11 | POST | `/institutions/me/children` | 아이 등록 | I-04 |
| O-13 | GET | `/children/{childId}` | 아동 상세 | I-09-1 |
| O-14 | GET | `/children/{childId}/context` | Child Context 타임라인 | I-09-1 |

> 이전 초안에 있던 `O-12 초대코드 재발급`은 초대코드 방식을 제외하면서 삭제했습니다.
> 번호는 혼동을 막기 위해 결번으로 둡니다.

**O-11 요청**

```json
{
  "name": "김하늘",
  "birthDate": "2017-03-14",
  "externalId": "2026-0031",
  "guardianPhoneLast4": "1234"
}
```

`externalId`(기관 내부 아동 ID)는 선택입니다. `guardianPhoneLast4`는 **보호자 연결
요청의 매칭 키**로 쓰이므로(§5.1) 사실상 필수에 가깝습니다.
**보호자 전화번호 전체는 받지 않습니다.**

**O-11 응답** · `201 Created`

```json
{
  "child": { "id": "child_9", "name": "김하늘", "birthDate": "2017-03-14",
             "status": "pending_consent", "institutions": [] }
}
```

등록 직후 아이는 `pending_consent` 상태입니다. 보호자가 카카오 로그인 후 이 기관과
연결하고 공유 범위에 동의해야 `active`가 되며, 그 전에는 기록을 올릴 수 없습니다.

**이 등록 시점에 보호자 연결 요청(pending link)이 함께 생성되어야 합니다.** 보호자가
로그인하면 G-01에서 이 요청을 보게 됩니다(§5.1).

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
| O-22 | GET | `/matching-queue` | 확인 필요 큐 | I-03, I-06 |
| O-23 | POST | `/matching-queue/{itemId}/resolve` | 아이 확정 / 제외 | I-06 |
| O-24 | GET | `/validation-results?status=BLOCK` | 수정 요청 큐 | I-03, I-07 |
| O-25 | POST | `/validation-results/{itemId}/resolve` | 재업로드 / 보류 | I-07 |

**O-20 요청** · `multipart/form-data`

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `file` | 예 | 다중 업로드 지원 여부 회신 필요 |
| `recordType` | 예 | `관찰일지` · `활동일지` · `특이사항` · `사진` |
| `capturedAt` | 예 | ISO 8601 |
| `childId` | 아니오 | 비우면 자동 매칭 |

- `pending_consent` 상태 아이의 기록은 **거부**해주세요 →
  `409 CHILD_CONSENT_PENDING`
- 용량 초과는 `413 FILE_TOO_LARGE`

**O-21 응답** · 파이프라인 단계는 프론트 `PIPELINE_STAGES`와 맞춥니다.

```json
{ "id": "raw_1", "stage": "validating", "stageIndex": 2, "totalStages": 5 }
```

| `stage` | 라벨 |
| --- | --- |
| `uploaded` | 기록 등록 |
| `matching` | 매칭 |
| `validating` | 검증 |
| `summarizing` | 요약 |
| `gate1_pending` | 1차 검토 대기 |

**O-22 응답** · `status`가 `auto`인 건은 포함하지 않습니다.

```json
[
  { "id": "mq_1",
    "record": { "id": "raw_3", "fileName": "0821_활동일지.docx", "type": "활동일지",
                "capturedAt": "2026-08-21T14:10:00+09:00", "preview": "…" },
    "status": "multi",
    "confidence": 0.62,
    "multiReason": "ambiguous_identity",
    "hintMismatch": false,
    "candidates": [ { "childId": "child_1", "name": "김하늘", "group": "햇살반" } ],
    "evidence": [ { "start": 12, "end": 15 } ] }
]
```

- `status`: `auto` · `review` · `multi` · `unmatched` · `failed` — AI 매칭 에이전트 실제 계약(`AI/matching/schemas.py`)값 그대로 (9/22 팀 확인, 9/24 DB초안 반영). `failed`는 AI 호출 자체가 실패했을 때만 쓰는 BE 전용 값
- `confidence`: 0.0~1.0, 항상 채워짐 (AI 계약 기본값 0.0) — `multi`면 후보 중 최고점과 같음
- `multiReason`: `status`가 `multi`일 때만 채워짐 — `ambiguous_identity` · `co_mention`
- `hintMismatch`: 표지 힌트와 다른 아동으로 판단했는지
- `evidence`: 판정 근거가 된 본문 구간(`{start, end}`, 유니코드 코드포인트 인덱스)
- `record`/`candidates[].{name,group}`은 아직 JournalEntry·Child 연동 전이라 BE가 못 채웁니다 — 현재는 없이 내려갑니다

**O-23 요청** · 프론트는 선택한 아이를 함께 보냅니다.

```json
{ "action": "assign", "childId": "child_1" }
```

| `action` | 의미 | `childId` |
| --- | --- | --- |
| `assign` | 선택한 아이로 확정 | 필수 |
| `not_ours` | 우리 기관 아동 아님 (제외) | 없음 |

> 현재 프론트 `resolveMatchingItem(id)`는 `childId`를 보내지 않습니다. 이 명세대로
> 프론트를 수정할 예정입니다.

**O-24 응답**

```json
[
  { "id": "bq_1",
    "record": { "id": "raw_5", "fileName": "0821_특이사항.txt", "type": "특이사항",
                "capturedAt": "2026-08-21T16:00:00+09:00", "preview": "…" },
    "childName": "김하늘",
    "violationReason": "다른 아이의 이름이 함께 적혀 있습니다" }
]
```

`violationReason`은 **화면에 그대로 노출**되므로 선생님이 읽고 바로 조치할 수 있는
한국어 문장으로 주세요. 코드값이 필요하면 `violationCode`를 별도로 추가해주세요.

**O-25 요청** · `{ "action": "reupload" }` 또는 `{ "action": "hold" }`

### 4.4 Gate 1 — 1차 검토

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-30 | GET | `/summaries?gate1Status=pending` | 검토 대기 요약 목록 | I-03, I-08 |
| O-31 | POST | `/summaries/{summaryId}/gate1` | 승인 / 수정 후 승인 / 반려 | I-08 |

**O-30 응답**

```json
[
  { "id": "sum_1", "childId": "child_1", "childName": "김하늘",
    "institutionName": "햇살아동발달센터", "date": "2026-08-21",
    "recordType": "관찰일지", "validation": "REVIEW",
    "content": "오전 활동에서 또래와 …",
    "flaggedSpan": "또래와 다툼이 있었던 것으로 보입니다",
    "flagReason": "관찰되지 않은 추정 표현",
    "gate1Status": "pending", "sourceCount": 2 }
]
```

- `flaggedSpan`은 `content` **안에 그대로 포함된 부분 문자열**이어야 합니다. 프론트가
  본문에서 그 문장을 찾아 하이라이트합니다.
- 프론트는 `gate1Status`가 `pending`과 `rejected`인 항목을 모두 화면에 씁니다
  (반려 이력 표시). 필터는 프론트에서 합니다.

**O-31 요청**

```json
{ "decision": "approve", "editedContent": "선생님이 수정한 본문" }
```

```json
{ "decision": "reject", "reason": "관찰하지 않은 내용이 포함됨" }
```

| 필드 | 조건 |
| --- | --- |
| `decision` | `approve` \| `reject` |
| `editedContent` | `approve` + 수정했을 때만 |
| `reason` | `reject`일 때 필수 |

**응답** · `{ "gate1Status": "approved" }`

승인 시 Child Context에 적재하고, 반려 시 저장하지 않고 원본으로부터 요약을 재생성해
다시 `pending`으로 만들어주세요.

### 4.5 Insight · Gate 2

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| O-40 | GET | `/insights` | Insight 목록 | I-03, I-10, I-11 |
| O-41 | GET | `/insights/{id}` | Insight 단건 | I-10 |
| O-42 | POST | `/insights/{insightId}/gate2` | 발송 승인 / 보류 | I-11 |

**O-40 응답**

```json
[
  { "id": "ins_1", "childId": "child_1", "childName": "김하늘",
    "period": "2026년 8월 3주차",
    "content": "오후 시간대 …",
    "primarySource": { "id": "inst_center_1", "name": "햇살아동발달센터",
                       "type": "center", "verified": true },
    "evidence": [
      { "childContextId": "ctx_10", "date": "2026-08-19", "label": "관찰일지 요약",
        "institution": { "id": "inst_center_1", "name": "햇살아동발달센터",
                         "type": "center", "verified": true } }
    ],
    "targets": [
      { "institution": { "id": "inst_school_1", "name": "○○초등학교",
                         "type": "school", "verified": true }, "consent": "granted" },
      { "institution": { "id": "inst_assist_1", "name": "△△활동지원",
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
{ "decision": "approve", "targetInstitutionIds": ["inst_school_1"] }
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
[ { "id": "inb_1",
    "from": { "id": "inst_school_1", "name": "○○초등학교", "type": "school", "verified": true },
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

초대코드 방식은 제외했습니다. 최초 연결은 **연결 요청(pending link)** 방식 하나로
갑니다.

```
기관: 아이 등록 (O-11)  →  서버가 보호자 연결 요청 생성
                                    ↓
보호자: 카카오 로그인 → 약관 동의 → 대기 중 연결 요청 확인 (G-01)
                                    ↓
        아이 정보 확인 → 기관 확인 → 공유 범위 동의 (G-02 → G-41)
```

보호자가 **나중에 다른 기관을 추가로 연결**할 때는 기관 코드 입력(G-43 · G-44, §5.5)을
씁니다. 최초 온보딩 경로는 아니고, 설정 화면(P-13)에서만 쓰입니다.

| # | 메서드 | 경로 | 설명 | 화면 |
| --- | --- | --- | --- | --- |
| G-01 | GET | `/guardians/me/pending-links` | 연결 대기 중인 아이 · 기관 목록 | P-02, P-11 |
| G-02 | GET | `/children/{childId}/consent-preview?institutionId=` | 아이 · 기관 · 공유 범위 미리보기 | P-02 |
| G-03 | POST | `/children/{childId}/links/{institutionId}/reject` | 아이 정보 불일치 반려 | P-02 |

**G-01 응답** · 이 보호자 계정에 연결 대기 중인 건입니다.

```json
[
  { "child": { "id": "child_9", "name": "김하늘", "birthDate": "2017-03-14" },
    "institution": { "id": "inst_school_1", "name": "○○초등학교",
                     "type": "school", "verified": true },
    "requestedAt": "2026-08-20T10:00:00+09:00" }
]
```

대기 건이 없으면 빈 배열을 주세요. 프론트는 "연결된 기관이 없습니다" 안내와 함께 기관
코드 입력(P-13)으로 안내합니다.

#### 매칭 키 — 확정 필요

이 방식은 **기관이 등록한 아이**와 **카카오 로그인한 보호자**를 서버가 이어줘야
동작합니다. 무엇을 기준으로 할지 정해주세요.

| 후보 | 내용 | 비고 |
| --- | --- | --- |
| 전화번호 대조 | 카카오 계정 전화번호 ↔ O-11의 `guardianPhoneLast4` | 뒤 4자리만으로는 충돌 가능. 아이 이름·생년월일과 조합해 후보를 좁힌 뒤 보호자가 화면에서 최종 확인하는 방식을 제안합니다 |
| 기관이 전체 번호 입력 | 확실하지만 **제품 규칙 위반** | 기관이 보호자 정보를 대신 입력하지 않기로 했습니다 |
| 기관 코드 병행 | 연결 요청이 안 잡히면 보호자가 기관 코드로 직접 연결 | 이미 구현된 P-13을 폴백으로 쓰면 됩니다 |

프론트는 **전화번호 대조 + 보호자 최종 확인 + 기관 코드 폴백** 조합을 권합니다.
카카오 동의 항목에 전화번호를 포함할지(§1-1)와 함께 결정해야 합니다.

**G-02 응답** · P-02 화면이 한 번에 그리는 정보입니다.

```json
{
  "child": { "name": "김하늘", "birthDate": "2017-03-14" },
  "institution": { "id": "inst_school_1", "name": "○○초등학교",
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
    { "id": "act_1", "at": "2026-08-21 15:20",
      "text": "○○초등학교에 관찰 요약이 전달되었습니다", "journalId": "jnl_3" }
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
  { "id": "jnl_3", "childId": "child_1",
    "institution": { "id": "inst_center_1", "name": "햇살아동발달센터",
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
  "trendEvidenceIds": ["jnl_1", "jnl_3"],
  "patterns": [ { "text": "이동 전 예고가 있으면 전환이 쉬웠습니다",
                  "evidenceIds": ["jnl_2"] } ],
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
[ { "institution": { "id": "inst_art_1", "name": "○○미술학원",
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
[ { "id": "req_1",
    "institution": { "id": "inst_school_1", "name": "○○초등학교",
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
| `KAKAO_AUTH_FAILED` | 401 | "카카오 로그인에 실패했어요. 다시 시도해주세요." |
| `TERMS_NOT_AGREED` | 403 | 약관 동의 화면으로 이동 |
| `INSTITUTION_CODE_NOT_FOUND` | 404 | "일치하는 기관을 찾지 못했어요. 코드를 다시 확인해주세요." (**G-44 연결 시도에만** 사용. 조회 G-43은 `data: null`로 응답) |
| `LINK_REQUEST_NOT_FOUND` | 404 | "연결 요청을 찾지 못했어요" |
| `LINK_ALREADY_EXISTS` | 409 | "이미 연결된 기관입니다" |
| `CONSENT_NOT_GRANTED` | 403 | Gate 2에서 미동의 기관 선택 시 |
| `CHILD_CONSENT_PENDING` | 409 | "보호자 동의가 완료되기 전에는 기록을 올릴 수 없습니다" |

### 기록 · 검토

| 코드 | 상태 | 설명 |
| --- | --- | --- |
| `FILE_TOO_LARGE` | 413 | 업로드 용량 초과 |
| `UNSUPPORTED_FILE_TYPE` | 400 | 허용되지 않은 확장자 |
| `CHILD_NOT_FOUND` | 404 | 없는 아이 |
| `RECORD_NOT_FOUND` | 404 | 없는 기록 |
| `SUMMARY_ALREADY_DECIDED` | 409 | 이미 승인/반려된 요약 재결정 |
| `INSIGHT_ALREADY_SENT` | 409 | 이미 발송된 Insight 재발송 |
| `NOT_PRIMARY_SOURCE` | 403 | 근거 제공 기관이 아닌 곳의 Gate 2 승인 시도 |

---

## 7. 구현 우선순위 제안

프론트가 화면별로 바로 붙일 수 있는 순서입니다.

| 순위 | 범위 | 엔드포인트 | 붙는 화면 |
| --- | --- | --- | --- |
| 1 | 인증 · 세션 | `/auth/kakao`, `/auth/me`, `/auth/terms`, `/auth/logout` | I-01, P-01, 전 화면 가드 |
| 2 | 아동 조회 | O-10, O-13, O-14 | I-09, I-09-1 |
| 3 | 기록 등록 · 큐 | O-20 ~ O-25 | I-05, I-06, I-07 |
| 4 | Gate 1 | O-30, O-31 | I-08, I-03 |
| 5 | 보호자 연결 · 동의 | G-01 ~ G-03, G-41 | P-01, P-02 |
| 6 | 보호자 열람 | G-10 ~ G-22 | P-03, P-07, P-08 |
| 7 | Insight · Gate 2 | O-40 ~ O-42 | I-10, I-11 |
| 8 | 동의 관리 · 알림 | G-40, G-42 ~ G-51 | P-04, P-11, P-12, P-13 |
| 9 | 수신함 · 리포트 · 상담 | O-50 ~ O-53, G-30 | I-12, I-13, I-14, P-09 |

각 단계가 끝나면 프론트는 `lib/api.ts`의 해당 함수 본문만 교체하면 되므로,
**엔드포인트 단위로 순차 연동이 가능합니다.** 전부 완성될 때까지 기다릴 필요는
없습니다 (`VITE_USE_MOCK`으로 섞어 쓸 수 있도록 함수별 스위치도 검토 중입니다).
