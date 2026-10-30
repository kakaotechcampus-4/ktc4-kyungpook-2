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
| `VITE_USE_MOCK` | `true` | 데이터(`lib/api.ts`). `false`여도 백엔드에 없는 기능은 mock (아래 참고) |
| `VITE_AUTH_MOCK` | `true` | 로그인(`lib/auth.ts`). 데이터와 분리돼 있습니다 |
| `VITE_AUTH_ORIGIN` | `""` | 로그인 진입 주소의 오리진. **dev 는 `http://localhost:8080`** |
| `VITE_API_BASE_URL` | `""` | 백엔드 origin. dev proxy·nginx를 쓰면 비워 둡니다 |

**mock 스위치가 둘인 이유** — 백엔드에 열려 있는 것이 인증과 원본 기록뿐이라, 하나로
묶으면 로그인을 켜는 순간 대시보드·아이 목록·게이트가 전부 빈 화면이 됩니다.
`VITE_AUTH_MOCK=false` + `VITE_USE_MOCK=true` 로 두면 **로그인만 실연동**하고 나머지는
mock 으로 유지할 수 있습니다. 엔드포인트가 열리는 대로 하나씩 옮겨갑니다.

**데이터 mock 은 기능별로 해제합니다** — `lib/api.ts`의 `BE_READY` 표에서 백엔드가 만든
기능만 `true`입니다. `VITE_USE_MOCK=false`여도 `false`인 기능은 mock 을 돌려주므로,
아직 없는 API 때문에 대시보드 같은 화면이 통째로 에러가 나지 않습니다.
백엔드가 기능을 열면 그 키만 `true`로 바꾸고 다시 빌드합니다. 목록과 그 처리 버튼은
같은 키로 묶여 있어 함께 켜집니다. `VITE_USE_MOCK=true`면 표와 상관없이 전부 mock 입니다.

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
| `MatchStatus` | `auto` · `review` · `multi` · `unmatched` | Matching Agent 결과. AI 쪽 이름과 맞춥니다 |
| `Gate1Status` | `pending` · `approved` · `hold` · `removed` · `confirmed` · `reverted` | 1차 검토(요약 승인) 상태. 반려는 없다. `confirmed` · `reverted` 는 요약할 내용 없음 전용. 목록(O-30)은 `pending` · `hold` 만 준다 |
| `Gate2Status` | `pending` · `approved` · `hold` | 공유 전 검토 상태. 승인이 곧 보호자 열람이라 `sent` 가 따로 없다 |
| `ConsentState` | `granted` · `not_granted` · `revoked` | 기관별 보호자 동의 상태 |
| `ChildStatus` | `pending_consent` · `active` · `suspended` | 아이 활성 상태 |
| `ConsentField` | `daily_summary` · `weekly_insight` | 동의 범위 필드 |

`MatchStatus`의 `auto`(자동 확정)는 큐에 나타나지 않습니다. 확인 필요 큐(`MatchingItem`)에는
`review` · `multi` · `unmatched`만 들어옵니다.

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
  // null · 없음 = 서버(#35)가 아직 못 채우는 값. 화면은 그 칸을 숨긴다
  record: { id: string; fileName: string; type?: RawRecord["type"] | null;
            capturedAt: string | null; preview: string | null;
            fullContent: string | null };   // 전체 본문(#112). 화면은 이걸 펼쳐 두고, evidence 도 이 기준
  status: "review" | "multi" | "unmatched" | "failed";
  // AI 가 지목한 아이. review 의 추천 아이는 여기로만 온다 — 이름은 명부에서 찾아 붙인다
  matchedChildId: string | null;
  // multi 일 때만 채워진다. 동명이인이면 후보가 둘 이상 남는다.
  // 이름·반이 같으므로 birthDate 가 유일한 구분 근거다. 삭제된 아이는 name·birthDate 가 null
  candidates: { childId: string; name: string | null; group?: string | null;
                birthDate: string | null }[];
  evidence: { start: number; end: number }[];   // 판정 근거 구간 (문자 인덱스)
  multiReason?: "co_mention" | "ambiguous_identity" | null;
  unmatchedReason?: "no_anchor" | "not_in_roster" | null;
  hintMismatch?: boolean;     // 표지 이름과 본문 판정이 어긋남
  hintName?: string | null;
}

interface BlockedItem {       // 재입력 요청 큐 한 건 (BLOCK 판정)
  id: string;
  record: RawRecord;
  childName: string | null;
  violationReason: string;    // 화면에 그대로 노출되는 한국어 문장
}

interface SummaryItem {       // Gate 1 검토 대기 · 보류함 한 건 (BE Gate1SummaryResponse)
  id: string;                 // BE 는 숫자. lib/api.ts 가 문자열로 바꾼다
  childId: string;
  childName: string;
  entryDate: string;          // YYYY-MM-DD
  revision: number;           // N회차 요약
  gate1Status: Gate1Status;   // BE status GATE1_PENDING → pending, HOLD → hold
  version: string;            // 결정할 때 그대로 돌려보낸다. 바뀌었으면 409 SUMMARY_CHANGED
  processing: boolean;        // 곧 요약이 바뀜 — 고치기 · 승인을 막는다
  content: string;
  claims: { index: number; text: string }[];   // 빈 배열 = 문장을 못 나눈 요약. 편집 불가
  noContent: boolean;         // 요약할 내용 없음(#244). content "" · claims []. 결정은 confirm · revert 만
  needsReview: boolean;       // 다른 아이 이름이 남았을 수 있음. 경고만
  reviewReasons: string[];    // ["다른아동이름"]
  uncoveredEntries: { journalEntryId: string; content: string }[];   // 승인하면 함께 끝남
  reviewFlags: { journalEntryId: string; issueTypes: string[] }[];
}

type Gate1Card =              // 승인 때 보내는 문장 카드. 원래 문장은 모두 한 번씩, 배열 순서 = 최종 순서
  | { index: number; action: "KEEP" | "DELETE" }
  | { index: number; action: "EDIT"; text: string }
  | { action: "ADD"; text: string };

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
  needsReview: boolean;       // 공유 전에 꼭 읽어야 하는 글인지. BE 는 지금 항상 true
  primarySource: Institution; // Gate 2 승인 주체 (근거를 낸 기관 중 학교 > 센터 > 활동지원사)
  evidence: EvidenceRef[];
  targets: { institution: Institution; consent: ConsentState }[]; // 승인하면 받을 후보. granted 인 곳만 실제로 받는다 (#233)
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
| `getChildren` | `() => Promise<ChildListItem[]>` — 명부 한 줄의 `consent` 는 우리 기관 기준 동의. 기관 화면은 `status` 대신 이걸로 판단 | I-05, I-06, I-09, I-13 |
| `getMyInstitution` | `() => Promise<MyInstitution>` — 기관 코드 포함 (O-01) | I-09 |
| `getLinkRequests` | `() => Promise<PendingLinkRequest[]>` (O-60) | I-09 |
| `acceptLinkRequest` | `(childId: string, externalId: string) => Promise<ChildListItem>` (O-61) | I-09 |
| `rejectLinkRequest` | `(childId: string) => Promise<void>` (O-62) | I-09 |
| `getChild` | `(id: string) => Promise<ChildListItem \| undefined>` — 상세 API 가 없어 명부가 실서버면 명부에서 찾음 | I-09-1 |
| `getTimeline` | `(childId: string) => Promise<TimelineEntry[]>` | I-09-1 |
| `getMatchingQueue` | `() => Promise<MatchingItem[]>` | I-03, I-06, 사이드바 배지 |
| `resolveMatchingItem` | `(id: string, resolution: { action: "assign"; childId: string } \| { action: "not_ours" }) => Promise<{ ok: true }>` | I-06 |
| `getBlockedQueue` | `() => Promise<BlockedItem[]>` | I-03, I-07, 사이드바 배지 |
| `resolveBlockedItem` | `(id: string, action: "reupload" \| "hold") => Promise<{ ok: true }>` | I-07 |
| `getGate1Queue` | `(gate1Status?: "pending" \| "hold") => Promise<SummaryItem[]>` | I-03, I-08 |
| `decideGate1` | `(summaryId: string, body: { decision: "approve" \| "hold" \| "remove" \| "confirm" \| "revert"; cards?: Gate1Card[]; version: string }) => Promise<{ summaryId: string }>` · `confirm` · `revert` 는 `noContent` 요약만 | I-08 |
| `reopenGate1` | `(summaryId: string) => Promise<{ summaryId: string }>` | I-08 |
| `getInsights` | `(gate2Status?: Gate2Status) => Promise<Insight[]>` — 기본 `pending`. 우리 기관이 승인 주체인 것만 온다 | I-03, I-10, I-11 |
| `getInsight` | `(id: string) => Promise<Insight \| undefined>` | I-10 |
| `decideGate2` | `(insightId: string, body: { decision: "approve" \| "hold" }) => Promise<{ insightId: string; gate2Status: Gate2Status; decision: string }>` — 받을 기관은 보내지 않는다. 409 `INSIGHT_ALREADY_REVIEWED` · `INSIGHT_SUPERSEDED` · `INSIGHT_MATERIAL_CONSENT_REVOKED`(승인만) | I-11 |
| `registerChild` | `({ name, birthDate, externalId }) => Promise<{ child: RegisteredChild; invitation: IssuedInvitation }>` — O-11. 등록과 함께 서버가 첫 초대 코드를 발급. 503 `INVITE_CODE_ISSUE_FAILED`면 아이도 등록되지 않음 | I-04 |
| `issueInvitation` | `(childId: string) => Promise<IssuedInvitation>` — O-15. 이전 코드는 그대로 살아 있음 | I-04, I-09-1 |
| `getLiveInvitations` | `(childId: string) => Promise<LiveInvitation[]>` — O-16. 만료 · 사용 · 취소된 코드는 빠짐 | I-09-1 |
| `cancelInvitation` | `(childId: string, invitationId: string) => Promise<void>` — O-17 | I-09-1 |
| `getChildGuardians` | `(childId: string) => Promise<ChildGuardian[]>` — O-18 | I-09-1 |
| `unlinkGuardian` | `(childId: string, guardianUserId: string) => Promise<void>` — O-19. 모든 기관에서 끊김 | I-09-1 |
| `getInbox` | `() => Promise<InboxItem[]>` — O-50. 본문 없음, `readAt` 이 null 이면 안 읽음 | I-12, 사이드바 배지 |
| `getInboxItem` | `(insightId: string) => Promise<InboxDetail>` — O-51. 본문 포함, 처음 열면 열람 시각 기록. 볼 수 없는 글은 404 `SHARING_NOT_FOUND` | I-12 상세 |
| `askChat` | `(childId: string, question: string) => Promise<ChatTurn>` | I-13 |
| `getActivity` | `() => Promise<ActivityLog[]>` | I-14 |

### 3.2 학부모

| 함수 | 시그니처 | 사용 화면 |
| --- | --- | --- |
| `acceptInvitation` | `(code: string) => Promise<AcceptedInvitation>` — G-04. 400 `INVALID_INVITE_CODE` · 409 `LINK_ALREADY_EXISTS` · 403 `PARENT_REQUIRED` 는 `ApiError` 로 | P-01 |
| `unlinkChild` | `(childId: string) => Promise<void>` — G-05 | P-02, P-04 |
| `getParentChildren` | `() => Promise<Child[]>` | 전 화면 (아이 전환) |
| `getParentHome` | `(childId: string) => Promise<{ child: Child; activity: ParentActivity[] }>` — `childId` 필수 | P-03, P-04, P-06, P-07 |
| `getTodaySummary` | `(childId: string) => Promise<string \| null>` | P-03, P-07 |
| `getJournal` | `(childId: string) => Promise<JournalEntry[]>` (최신순) | P-03, P-07, P-10 |
| `getJournalEntry` | `(id: string) => Promise<JournalEntry \| undefined>` | P-08 |
| `flagJournalEntry` | `(id: string) => Promise<{ ok: true }>` | P-08 |
| `getCareReport` | `(childId: string, period: "weekly" \| "monthly") => Promise<CareReport \| null>` | P-09 |
| `getPendingInstitutionRequests` | `(childId: string) => Promise<{ institution: Institution }[]>` | P-03, P-04, P-07, P-09, P-11 |
| `updateConsent` | `(childId: string, institutionId: string, body: { action: "grant" \| "revoke"; allowedFields?: ConsentField[] }) => Promise<ConsentResult>` | P-04(끄기), P-11 |
| `lookupInstitutionByCode` | `(code: string) => Promise<Institution \| null>` | P-13 |
| `addInstitutionByCode` | `(childId: string, code: string) => Promise<{ institution: Institution; status: "pending"; requestedAt: string }>` — 연결 **요청**. 실패는 `ApiError` | P-13 |
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
type Session = { loggedIn: boolean; signupCompleted: boolean; role: Role; name?: string;
                 institutionName?: string };   // 기관 계정만 (#236). 기관 사이드바 하단에 표시

getSession(): Promise<Session>               // GET /api/v1/auth/me — 401 이면 loggedIn: false
signup(input: SignupInput): Promise<Session> // POST /api/v1/auth/signup — 실패는 ApiError(code)
homePathFor(role: "org" | "parent"): string  // 가입을 마친 사람의 첫 화면
grantRole(role: "org" | "parent"): void      // mock 전용 — 실연동에서는 아무것도 하지 않습니다
clearSession(): void                         // 401 을 받았을 때 로컬 표시를 지웁니다
isAuthMock(): boolean                        // mock 모드인지 (로그인 화면이 참조)

startKakaoLogin(intent): void                // /oauth2/authorization/kakao 로 페이지 이동
takeLoginIntent(): "org" | "parent" | null   // 누른 버튼 — 회원가입 화면에서 역할을 미리 고르는 데만 씁니다
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
- **역할과 로그인 여부는 서버에 묻습니다.** `getSession()`이 화면에 들어갈 때마다
  `GET /api/v1/auth/me`를 부르고(api-spec §2.2), 분기는 `signupCompleted`만 봅니다.
  `false`면 카카오 로그인만 하고 역할을 안 고른 상태라 `/signup`으로 보냅니다.
- 역할은 회원가입 `POST /api/v1/auth/signup`에서 사용자가 고릅니다(§2.6). 보호자는 `role`만
  보냅니다 — 기관 필드가 `""`로라도 섞이면 400이라 `signup()`이 역할별로 본문을 새로 만듭니다.
- `grantRole`은 **mock 전용**입니다. "mock 데이터로 둘러보기" 버튼이 역할을 로컬(`itda_role`)에
  적고, mock 모드의 `getSession()`이 그 값을 읽습니다.
- **state 검증은 프론트에 없습니다.** Spring Security 가 서버에서 처리합니다.

### 역할 경계

| 레이아웃 라우트 | 통과 조건 | 실패 시 |
| --- | --- | --- |
| `app/routes/org/layout.tsx` | `role === "org"` | 가입 미완료면 `/signup?role=org`, 그 밖은 `/login` |
| `app/routes/parent/guard.tsx` | `role === "parent"` + 연결된 아이 1명 이상 | 가입 미완료면 `/signup?role=parent`, 그 밖은 `/parent/invite` |
| `app/routes/signup.tsx` | 로그인 + 가입 미완료 | 비로그인이면 `/login`, 가입 완료면 역할별 첫 화면 |

`clientLoader`가 끝나기 전에는 로딩 화면(`root.tsx`의 `HydrateFallback`)만 보입니다.
다른 역할의 화면이 순간 노출되는 경우는 없습니다.

`/parent/invite`와 `/parent/consent`는 guard **바깥**에 있어 로그인 없이 접근할 수
있습니다(초대 링크로 처음 들어오는 보호자).

---

## 5. 클라이언트 상태 인터페이스

서버에서 오지 않는, 프론트가 자체적으로 들고 있는 상태입니다.

| 이름 | 저장소 | 키 | 용도 |
| --- | --- | --- | --- |
| 역할 | localStorage | `itda_role` | mock 모드의 라우트 가드 (실연동은 `/auth/me`) |
| 로그인 의도 | sessionStorage | `itda_login_intent` | 회원가입 화면에서 역할을 미리 고름 |
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
| `ConfirmDialog` | 되돌릴 수 없는 결정 앞의 확인 창 (Gate 1 · Gate 2). Esc · 취소로 닫힘 |
| `PipelineStepper` | 기록 등록 → 매칭 → 검증 → 요약 → 1차 검토 대기 |
| `QueueCard` · `PageHeader` · `Card` · `EmptyState` · `Note` | 기관 화면 레이아웃 공통 |
| `InstitutionChip` · `InstitutionIcon` | 기관 표시 |
| `Toggle` · `StepProgress` · `ParentPageHeader` | 학부모 화면 공통 |
