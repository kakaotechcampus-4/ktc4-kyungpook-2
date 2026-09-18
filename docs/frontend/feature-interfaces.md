| `VITE_AUTH_ORIGIN` | `""` | 로그인 진입 주소의 오리진. **dev 는 `http://localhost:8080`** |
# 프론트엔드 기능 인터페이스

> 대상: 백엔드 · AI · 기획 팀원
> 기준 코드: `frontend/app/lib/types.ts`, `frontend/app/lib/api.ts`, `frontend/app/lib/auth.ts`
> 상태: **화면 구현 완료 · mock 데이터로 동작 중**. 실제 API 연결 전.

프론트엔드는 화면 코드가 백엔드를 직접 호출하지 않고 **심(seam) 계층** 하나만 통해
데이터를 얻습니다. 이 문서는 그 심의 계약, 즉 "프론트가 무엇을 주고 무엇을 받기로
했는지"를 정리한 것입니다. 백엔드 DTO가 확정되면 이 문서와 `types.ts`가 서로의
기준이 됩니다.

관련 문서:

| 문서 | 역할 |
| --- | --- |
| [screen-specs.md](screen-specs.md) | 화면별 기능 명세 (기관 15 · 학부모 13) |
| [feature-spec.md](feature-spec.md) | 전체 기능 명세 · 상태 전이 · 제품 규칙 |
| [../api/api-spec.md](../api/api-spec.md) | 백엔드 요청용 API 명세 |
| [../api/api-conventions.md](../api/api-conventions.md) | 응답 래퍼 · 오류 코드 · HTTP 상태 규약 |

---

## 1. 계층 구조

```
화면(routes/*.tsx)
  ├─ clientLoader()  ─────┐
  └─ 이벤트 핸들러    ─────┤
                          ▼
       lib/api.ts (데이터)              lib/auth.ts (로그인)
                │                              │
      ┌─────────┴─────────┐          ┌─────────┴─────────┐
  VITE_USE_MOCK        =false    VITE_AUTH_MOCK       =false
  lib/mock/data.ts     fetch()   로컬 역할만           BE oauth2Login
```

- 화면 코드는 `lib/api.ts`의 **함수 이름과 시그니처**에만 의존합니다.
- 백엔드가 붙으면 각 함수의 **본문만** 교체합니다. 화면은 수정하지 않습니다.
- 즉, 백엔드가 자유롭게 정해도 되는 것은 "경로·요청 형식"이고, 바꾸면 화면까지 영향을
  주는 것은 "이 문서에 적힌 반환 타입"입니다.

### 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `VITE_USE_MOCK` | `true` | 데이터(`lib/api.ts`). `false`일 때만 실제 HTTP 호출 |
| `VITE_AUTH_MOCK` | `true` | 로그인(`lib/auth.ts`). 데이터와 분리돼 있습니다 |
| `VITE_AUTH_ORIGIN` | `""` | 로그인 진입 주소의 오리진. **dev 는 `http://localhost:8080`** |
| `VITE_API_BASE_URL` | `""` | 백엔드 origin. dev proxy·nginx를 쓰면 비워 둡니다 |

**mock 스위치가 둘인 이유** — 백엔드에 열려 있는 것이 인증과 원본 기록뿐이라, 하나로
묶으면 로그인을 켜는 순간 대시보드·아이 목록·게이트가 전부 빈 화면이 됩니다.
`VITE_AUTH_MOCK=false` + `VITE_USE_MOCK=true` 로 두면 **로그인만 실연동**하고 나머지는
mock 으로 유지할 수 있습니다. 엔드포인트가 열리는 대로 하나씩 옮겨갑니다.

`VITE_*`는 빌드 시점에 번들에 포함됩니다. 값이 바뀌면 재빌드가 필요합니다.

---

## 2. 도메인 타입

`frontend/app/lib/types.ts`가 원본입니다. 백엔드 응답 필드명은 이 타입에 맞추는 것을
기본으로 하되, 다르면 [api-spec.md](../api/api-spec.md)의 "확정이 필요한 사항"에서
합의합니다.

### 2.1 열거형

| 타입 | 값 | 의미 |
| --- | --- | --- |
| `InstitutionType` | `school` · `center` · `assistant` | 학교 · 센터 · 활동지원사 |
| `ValidationStatus` | `PASS` · `REVIEW` · `BLOCK` | Validation Agent 판정 |
| `MatchStatus` | `confirmed` · `multi` · `unmatched` · `low` | Matching Agent 결과 |
| `Gate1Status` | `pending` · `approved` · `rejected` | 1차 검토(요약 승인) 상태 |
| `Gate2Status` | `pending` · `approved` · `held` · `sent` | 공유 전 검토(발송) 상태 |
| `ConsentState` | `granted` · `not_granted` · `revoked` | 기관별 보호자 동의 상태 |
| `ChildStatus` | `pending_consent` · `active` · `suspended` | 아이 활성 상태 |
| `ConsentField` | `daily_summary` · `weekly_insight` | 동의 범위 필드 |

`MatchStatus`의 `confirmed`는 큐에 나타나지 않습니다. 확인 필요 큐(`MatchingItem`)에는
`multi` · `unmatched` · `low`만 들어옵니다.

### 2.2 공통 엔티티

```ts
interface Institution {
  id: string;
  name: string;
  type: InstitutionType;
  verified: boolean;          // 증빙서류 검증 완료 여부
}

interface Child {
  id: string;
  name: string;
  birthDate: string;          // YYYY-MM-DD
  school?: string;            // 학교/학년 표시용
  status: ChildStatus;
  institutions: { institution: Institution; consent: ConsentState }[];
  care?: ChildCareInfo;
}

interface ChildCareInfo {     // 보호자가 직접 입력. 기관 유형별로 다르게 공유된다
  welfareCard: boolean;
  allergies: string[];
  medications: { name: string; time: string }[];
  weeklySchedule: { day: string; note: string }[];
}

interface RawRecord {
  id: string;
  fileName: string;
  type: "관찰일지" | "활동일지" | "특이사항" | "사진";
  capturedAt: string;         // ISO 8601
  preview: string;
}
```

### 2.3 기관 화면 전용 타입

```ts
interface MatchingItem {      // 확인 필요 큐 한 건
  id: string;
  record: RawRecord;
  status: "multi" | "unmatched" | "low";
  confidence: number | null;  // 0.0 ~ 1.0, unmatched면 null
  candidates: { childId: string; name: string; group: string }[];
}

interface BlockedItem {       // 재입력 요청 큐 한 건 (BLOCK 판정)
  id: string;
  record: RawRecord;
  childName: string | null;
  violationReason: string;    // 화면에 그대로 노출되는 한국어 문장
}

interface SummaryItem {       // Gate 1 검토 대기 한 건
  id: string;
  childId: string;
  childName: string;
  institutionName: string;
  date: string;               // YYYY-MM-DD
  recordType: RawRecord["type"];
  validation: ValidationStatus;
  content: string;            // AI 요약 본문 (선생님이 편집 가능)
  flaggedSpan?: string;       // REVIEW 사유가 된 문장 — 본문 안에서 하이라이트
  flagReason?: string;
  gate1Status: Gate1Status;
  rejectReason?: string;
  sourceCount: number;        // 근거가 된 원본 기록 수
}

interface TimelineEntry {     // Child Context 타임라인 한 칸 (= 하루)
  date: string;
  entry: {                    // null = 그 날 기록 없음. 절대 추정치로 채우지 않는다
    recordType: RawRecord["type"];
    validation: ValidationStatus;
    content: string;
    sourceCount: number;
    version: number;
    edited: boolean;          // 선생님이 수정했는지
  } | null;
}

interface Insight {
  id: string;
  childId: string;
  childName: string;
  period: string;             // 예: "2026년 8월 3주차"
  content: string;
  primarySource: Institution; // 근거 최다 제공 기관 = Gate 2 승인 주체
  evidence: EvidenceRef[];
  targets: { institution: Institution; consent: ConsentState }[];
  gate2Status: Gate2Status;
}

interface EvidenceRef {
  childContextId: string;
  date: string;
  label: string;
  institution: Institution;   // 어느 기관 기록인지 승인자가 확인해야 한다
}

interface InboxItem {
  id: string;
  from: Institution;
  childName: string;
  receivedAt: string;
  content: string;            // 원본이 아니라 수신자별로 변환된 최소 정보
  read: boolean;
}

interface ChatTurn {
  question: string;
  answer: string | null;      // null = 근거 없음. 답변을 생성하지 않는다
  sources: { date: string; label: string }[];
}

interface ActivityLog {
  id: string;
  at: string;
  actor: string;
  action: string;
  target: string;
}
```

### 2.4 학부모 화면 전용 타입

```ts
interface JournalEntry {
  id: string;
  childId: string;
  institution: Institution;
  date: string;               // YYYY-MM-DD
  time: string;               // HH:mm
  isNew: boolean;
  tag: string;                // 예: "활동", "식사"
  summary: string;            // 목록에 보이는 한 줄
  detail: string;             // 상세 화면 본문
  institutionNote?: string;   // "기관에서 남긴 말"
  photoCount?: number;
  flagged?: boolean;          // 보호자가 "이 내용이 이상해요"를 눌렀는지
}

interface CareReport {
  period: "weekly" | "monthly";
  rangeLabel: string;         // 예: "8월 15일 ~ 8월 21일"
  summary: string;
  trendTitle: string;
  trend: { label: string; value: number }[];
  trendInsight: string;
  trendEvidenceIds: string[]; // JournalEntry.id 배열
  patterns: { text: string; evidenceIds: string[] }[];
  tips: string[];
}

interface InstitutionRequestItem {   // 설정 › 기관 요청사항
  id: string;
  institution: Institution;
  status: "confirmed" | "needs_check";
  items: string[];            // 준비물 · 확인사항
}

interface ParentActivity {
  id: string;
  at: string;
  text: string;
  journalId?: string;         // 있으면 일지 상세로 이동 가능
}
```

---

## 3. API 인터페이스 (`lib/api.ts`)

현재 각 함수 주석에 달린 경로는 **기획서 11절 기준의 제안값**입니다. 실제 경로와
요청·응답 형식은 [api-spec.md](../api/api-spec.md)에서 확정합니다.

### 3.1 기관

| 함수 | 시그니처 | 사용 화면 |
| --- | --- | --- |
| `getChildren` | `() => Promise<Child[]>` | I-05, I-09, I-13 |
| `getChild` | `(id: string) => Promise<Child \| undefined>` | I-09-1 |
| `getTimeline` | `(childId: string) => Promise<TimelineEntry[]>` | I-09-1 |
| `getMatchingQueue` | `() => Promise<MatchingItem[]>` | I-03, I-06, 사이드바 배지 |
| `resolveMatchingItem` | `(id: string) => Promise<{ ok: true }>` | I-06 |
| `getBlockedQueue` | `() => Promise<BlockedItem[]>` | I-03, I-07, 사이드바 배지 |
| `resolveBlockedItem` | `(id: string, action: "reupload" \| "hold") => Promise<{ ok: true }>` | I-07 |
| `getGate1Queue` | `() => Promise<SummaryItem[]>` | I-03, I-08 |
| `decideGate1` | `(summaryId: string, body: { decision: "approve" \| "reject"; edited_content?: string; reason?: string }) => Promise<{ gate1_status: Gate1Status }>` | I-08 |
| `getInsights` | `() => Promise<Insight[]>` | I-03, I-10, I-11 |
| `getInsight` | `(id: string) => Promise<Insight \| undefined>` | I-10 |
| `decideGate2` | `(insightId: string, body: { decision: "approve" \| "hold"; target_institution_ids: string[] }) => Promise<{ gate2_status: Gate2Status }>` | I-11 |
| `registerChild` | `({ name, birthDate }) => Promise<{ child: Child; inviteCode: string }>` | I-04 |
| `generateInviteCode` | `() => string` — **클라이언트 임시 구현. 서버 발급으로 대체 예정** | I-04 |
| `getInbox` | `() => Promise<InboxItem[]>` | I-12, 사이드바 배지 |
| `askChat` | `(childId: string, question: string) => Promise<ChatTurn>` | I-13 |
| `getActivity` | `() => Promise<ActivityLog[]>` | I-14 |

### 3.2 학부모

| 함수 | 시그니처 | 사용 화면 |
| --- | --- | --- |
| `getParentChildren` | `() => Promise<Child[]>` | 전 화면 (아이 전환) |
| `getParentHome` | `(childId?: string) => Promise<{ child: Child; activity: ParentActivity[] }>` | P-03, P-04, P-06, P-07 |
| `getTodaySummary` | `(childId: string) => Promise<string \| null>` | P-03, P-07 |
| `getJournal` | `(childId: string) => Promise<JournalEntry[]>` (최신순) | P-03, P-07, P-10 |
| `getJournalEntry` | `(id: string) => Promise<JournalEntry \| undefined>` | P-08 |
| `flagJournalEntry` | `(id: string) => Promise<{ ok: true }>` | P-08 |
| `getCareReport` | `(childId: string, period: "weekly" \| "monthly") => Promise<CareReport \| null>` | P-09 |
| `getPendingInstitutionRequests` | `(childId: string) => Promise<{ institution: Institution }[]>` | P-03, P-04, P-07, P-09, P-11 |
| `declineInstitutionRequest` | `(childId: string, institutionId: string) => Promise<{ ok: true }>` | P-11 |
| `updateConsent` | `(childId: string, institutionId: string, body: { allowed_fields: string[]; action: "grant" \| "revoke" }) => Promise<{ ok: true }>` | P-02, P-04, P-11 |
| `lookupInstitutionByCode` | `(code: string) => Promise<Institution \| null>` | P-13 |
| `addInstitutionByCode` | `(childId: string, code: string) => Promise<{ institution: Institution } \| null>` | P-13 |
| `updateChildCare` | `(childId: string, care: ChildCareInfo) => Promise<{ ok: true }>` | P-06 |
| `getInstitutionRequests` | `(childId: string) => Promise<InstitutionRequestItem[]>` | P-04, P-12 |
| `confirmInstitutionRequest` | `(id: string) => Promise<{ ok: true }>` | P-12 |

### 3.3 아이별 조회 규칙 (중요)

학부모 화면의 모든 데이터 조회는 **반드시 `childId`를 인자로 받습니다.** 리포트,
기관 요청사항, 타임라인, 알림 배지 어느 것도 "현재 선택된 아이"를 서버가 추측해서는
안 됩니다. 다자녀 보호자에게 다른 아이의 데이터가 섞여 보이는 사고로 직결됩니다.

`getPendingInstitutionRequests`는 알림 배지의 **단일 기준점**입니다. 홈 · 타임라인 ·
리포트 · 설정 헤더의 빨간 점이 전부 이 함수 하나를 봅니다. 같은 조건을 화면마다 따로
구현하지 않습니다.

---

## 4. 인증 인터페이스 (`lib/auth.ts`)

```ts
type Role = "org" | "parent" | null;

getSession(): Promise<{ role: Role }>        // 현재 세션의 역할
grantRole(role: "org" | "parent"): void      // 역할을 로컬에 기록 — 로그인이 아닙니다
clearSession(): void                         // 401 을 받았을 때 로컬 표시를 지웁니다
isAuthMock(): boolean                        // mock 모드인지 (로그인 화면이 참조)

startKakaoLogin(): void                      // /oauth2/authorization/kakao 로 페이지 이동
signOut(): Promise<void>                     // POST /api/v1/auth/logout — 서버가 쿠키를 지웁니다

readCsrfToken(): string | null               // XSRF-TOKEN 쿠키
csrfHeader(): Record<string, string>         // { "X-XSRF-TOKEN": ... } 또는 {}
```

- **출입증은 `access_token` httpOnly 쿠키입니다.** 프론트가 읽지도 지우지도 못합니다.
  요청에 붙이는 일은 브라우저가 자동으로 하고(`lib/api.ts` 의 `credentials: "include"`),
  지우는 일은 서버에 부탁합니다(`signOut()`).
- **CSRF 토큰이 필요합니다.** 쿠키가 자동 전송되므로 남의 사이트 폼에도 실립니다.
  백엔드가 `XSRF-TOKEN` 쿠키(이것만 httpOnly 가 아님)를 내려주고, `lib/api.ts` 가
  쓰기 요청마다 `X-XSRF-TOKEN` 헤더로 되돌립니다. 없으면 403 입니다.
- `grantRole`은 **로그인이 아닙니다.** 백엔드에 사용자 테이블이 없어 JWT의 subject가
  kakaoId뿐이고, 서버가 기관/학부모를 구분하지 못해 클라이언트가 임시로 들고 있습니다.
  학부모 온보딩(P-02)과 로그인 착지 페이지(I-01b)가 부릅니다.
  출입증이 쿠키가 된 지금은 이 값이 **로그인 여부의 표시**도 겸합니다.
- `GET /api/v1/auth/me`(역할 포함)가 열리면 `getSession()`/`grantRole()` **내부만** 그
  응답으로 바꿉니다. 호출부(각 라우트의 `clientLoader`)는 그대로 둡니다.
- **state 검증은 프론트에 없습니다.** Spring Security 가 서버에서 처리합니다.

### 역할 경계

| 레이아웃 라우트 | 통과 조건 | 실패 시 |
| --- | --- | --- |
| `app/routes/org/layout.tsx` | `role === "org"` | `/login` 리다이렉트 |
| `app/routes/parent/guard.tsx` | `role === "parent"` + 연결된 아이 1명 이상 | `/parent/invite` 리다이렉트 |

`clientLoader`가 끝나기 전에는 로딩 화면(`root.tsx`의 `HydrateFallback`)만 보입니다.
다른 역할의 화면이 순간 노출되는 경우는 없습니다.

`/parent/invite`와 `/parent/consent`는 guard **바깥**에 있어 로그인 없이 접근할 수
있습니다(초대 링크로 처음 들어오는 보호자).

---

## 5. 클라이언트 상태 인터페이스

서버에서 오지 않는, 프론트가 자체적으로 들고 있는 상태입니다.

| 이름 | 저장소 | 키 | 용도 |
| --- | --- | --- | --- |
| 역할 | localStorage | `itda_role` | 라우트 가드 (임시) |
| 선택된 아이 | localStorage | `itda_selected_child` | 다자녀 전환 |

```ts
// lib/selectedChild.ts
readSelectedChildId(kids: { id: string }[]): string   // 저장값이 목록에 없으면 첫째로 대체
writeSelectedChildId(id: string): void

// components/parent/ChildContext.tsx
useChildContext(): { kids: Child[]; selected: Child; selectChild: (id: string) => void }
```

아이를 전환하면 `writeSelectedChildId()` → `revalidate()` 순서로 현재 라우트의
`clientLoader`를 다시 실행해 새 아이 기준으로 화면 데이터를 갱신합니다.

상태 관리 라이브러리(TanStack Query · zustand)는 아직 도입하지 않았습니다. 실제 API가
붙는 시점에 추가합니다.

---

## 6. 오류 인터페이스

```ts
class ApiError extends Error {
  status: number;   // HTTP 상태 코드
  code: string;     // 서비스 오류 코드 (UPPER_SNAKE_CASE)
  message: string;
}
```

`request()`는 `res.ok`가 false면 `ApiError`를 던집니다.

> ⚠️ **현재 코드와 규약의 불일치 — 프론트가 고칠 부분**
>
> - `request()`가 오류 바디에서 `body.error_code`를 읽고 있지만,
>   [api-conventions.md](../api/api-conventions.md)가 정한 필드명은 `code`입니다.
> - 성공 응답의 `{ result, data }` 래퍼를 벗기지 않고 본문을 그대로 반환합니다.
>
> 실제 API 연결 시 `request()` 안에서 `data` 언래핑과 `code` 파싱을 함께 처리합니다.
> **백엔드는 규약대로 구현하면 되고, 이 수정은 프론트 몫입니다.**

---

## 7. 공유 UI 컴포넌트

여러 화면에서 같은 의미를 같은 모양으로 보여주기 위한 컴포넌트입니다
(`app/components/ui/index.tsx`). 상태를 색 단독으로 전달하지 않고 **항상 텍스트 라벨을
함께** 표시합니다.

| 컴포넌트 | 용도 |
| --- | --- |
| `ValidationBadge` | PASS / REVIEW / BLOCK 배지 |
| `ConsentStatus` | granted / not_granted / revoked 라벨 |
| `ConfidenceWarning` | 매칭 확신도 경고 |
| `FlaggedText` | 본문 안 REVIEW 사유 문장 하이라이트 |
| `EvidenceChip` | 근거 기록 칩 (날짜 · 라벨 · 기관 · 링크) |
| `IrreversibleWarning` | "되돌릴 수 없습니다" 경고 (Gate 2 전용) |
| `PipelineStepper` | 기록 등록 → 매칭 → 검증 → 요약 → 1차 검토 대기 |
| `QueueCard` · `PageHeader` · `Card` · `EmptyState` · `Note` | 기관 화면 레이아웃 공통 |
| `InstitutionChip` · `InstitutionIcon` | 기관 표시 |
| `Toggle` · `StepProgress` · `ParentPageHeader` | 학부모 화면 공통 |
