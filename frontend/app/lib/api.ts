/**
 * API 심(seam).
 *
 * 함수마다 mock 과 실제 호출(`request()`)을 둘 다 갖고 있고, 어느 쪽을 쓸지는
 * 아래 `BE_READY` 의 기능별 스위치가 정한다. 백엔드가 기능을 열면 그 키만 true 로
 * 바꾼다 — 화면 코드는 건드리지 않는다.
 * (멘토 피드백: "인터페이스만 맞게 가짜 API 연결한 후 나중에 실제 API 연결하기")
 *
 * ── 경로 ───────────────────────────────────────────────
 * 모든 경로에 `/api/v1` 을 붙인다 — "새 외부 API의 기본 경로는 /api/v1이다"
 * (docs/api/api-conventions.md). 예외로 `GET /api/health` 만 v1 이 없다.
 *
 * ⚠️ **아래 목록에 없는 경로는 아직 백엔드에 구현돼 있지 않다.** 경로는 위 규약을
 *    따른 추정이므로, 백엔드가 만들 때 실제 경로를 확인하고 맞춰야 한다.
 *
 *    구현된 것 (2026-10-26 기준. BE_READY 가 false 인 기능도 있다 — 각 키의 설명 참고)
 *      POST /api/v1/raw-records       multipart: file → 201
 *      GET  /api/v1/raw-records/{id}
 *      GET  /api/v1/raw-records
 *      GET  /api/v1/raw-records/progress
 *      GET  /api/v1/summaries?gate1Status=pending|hold
 *      POST /api/v1/summaries/{summaryId}/gate1
 *      POST /api/v1/summaries/{summaryId}/reopen
 *      GET  /api/v1/summaries/failed
 *      POST /api/v1/summaries/failed/retry
 *      POST /api/v1/summaries/failed/close
 *      GET  /api/v1/dashboard/pipeline
 *      GET  /api/v1/dashboard/ai-activity
 *      GET  /api/v1/system/ai-status
 *      GET  /api/v1/insights?gate2Status=pending|approved|hold
 *      POST /api/v1/insights/{insightId}/gate2
 *      GET  /api/v1/children/{childId}/consent-preview?institutionId=
 *      PUT  /api/v1/children/{childId}/consent-scopes/{institutionId}
 *      GET  /api/v1/institutions/me/children
 *      POST /api/v1/institutions/me/children   → 201 (초대 코드 포함)
 *      POST   /api/v1/institutions/me/children/{childId}/invitations        → 201
 *      GET    /api/v1/institutions/me/children/{childId}/invitations
 *      DELETE /api/v1/institutions/me/children/{childId}/invitations/{id}   → 204
 *      GET    /api/v1/institutions/me/children/{childId}/guardians
 *      GET    /api/v1/institutions/me/children/{childId}/timeline
 *      DELETE /api/v1/institutions/me/children/{childId}/guardians/{userId} → 204
 *      POST   /api/v1/guardians/me/invitations/accept
 *      DELETE /api/v1/guardians/me/children/{childId}   → 204 (아래 guardianUnlink 참고)
 *      GET    /api/v1/guardians/me/children
 *      GET    /api/v1/guardians/me/home?childId=
 *      GET    /api/v1/children/{childId}/institution-requests/pending
 *      GET    /api/v1/children/{childId}/journal
 *      GET    /api/v1/journal/{id}
 *      PUT    /api/v1/children/{childId}/care-info
 *      GET    /api/v1/guardians/me/insights
 *      GET    /api/v1/guardians/me/insights/{insightId}
 *      GET  /api/v1/matching-queue
 *      POST /api/v1/matching-queue/{id}/resolve
 *      GET  /api/v1/validation-results?status=BLOCK
 *      POST /api/v1/validation-results/{id}/resolve
 *      POST /api/v1/validation-results/{id}/revise
 *      GET  /api/v1/auth/me          (lib/auth.ts 에서 호출)
 *      POST /api/v1/auth/signup       (lib/auth.ts 에서 호출)
 *      POST /api/v1/auth/logout       (lib/auth.ts 에서 호출)
 *
 *    institutionId 는 서버가 인증 정보에서 가져간다 — 클라이언트가 보내지 않는다.
 *
 * ── 응답 ───────────────────────────────────────────────
 * 성공 { result, data, message? } · 실패 { result, code, message }
 * request() 가 래퍼를 벗겨 data 만 돌려준다. 화면은 래퍼를 모른다.
 *
 * ── 인증 ───────────────────────────────────────────────
 * Spring Security oauth2Login + httpOnly 쿠키.
 * 로그인 진입 GET /oauth2/authorization/kakao — 백엔드가 전부 처리한다.
 * 출입증은 access_token 쿠키. 쓰기 요청에는 X-XSRF-TOKEN 헤더가 필요하다.
 */

import { ApiError } from "@/lib/apiError";
import { clearSession, csrfHeader, getSession, isAuthMock, isOnboarded, markOnboarded } from "@/lib/auth";
import * as mock from "@/lib/mock/data";
import { forgetParentChildren, shareParentChildren } from "@/lib/parentChildrenCache";
import { GATE1_INDEX, MATCHING_INDEX } from "@/lib/pipeline";
import type {
  AcceptedInvitation,
  FailedSummary,
  AiActivity,
  AiProgress,
  AiStatus,
  BlockedItem,
  Child,
  ChildCareInfo,
  ChildGuardian,
  ChildListItem,
  ConsentField,
  ConsentPreview,
  ConsentResult,
  FileProgress,
  Gate1Card,
  Gate1Status,
  Gate2Status,
  GuardianInsight,
  InboxDetail,
  InboxItem,
  Insight,
  Institution,
  InstitutionShares,
  ChecklistItem,
  SentChecklist,
  SentChecklistPage,
  IssuedInvitation,
  JournalEntry,
  LiveInvitation,
  MatchingItem,
  MatchResolution,
  MyInstitution,
  ParentActivity,
  PendingLinkRequest,
  Pipeline,
  PipelineRow,
  RegisteredChild,
  SummaryItem,
  TimelineEntry,
  UploadFailReason,
  UploadResult,
} from "@/lib/types";

/**
 * mock 모드에서 GET 함수들은 `mock.*` 배열을 그대로 참조로 돌려준다(복사하지 않음).
 * 그래서 아래 mutate 계열 함수들이 그 배열을 in-place 로 고치면, 다음 GET 호출이나
 * 다른 라우트(사이드바 배지 등)가 즉시 바뀐 상태를 보게 된다 — 별도 서버 없이도
 * "승인 이력이 유지된다"를 흉내낼 수 있는 이유.
 */

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "false";
const BASE = import.meta.env.VITE_API_BASE_URL ?? "";

/**
 * 기능별 mock 스위치. **백엔드가 만든 기능만 true** 로 둔다.
 *
 * `VITE_USE_MOCK=false` 여도 false 인 기능은 mock 을 돌려준다. 하나로 묶어 두면
 * 실연동 빌드에서 아직 없는 API 가 404 를 내고, 그 화면(대시보드·사이드바 배지)이
 * 통째로 에러 화면이 된다. 백엔드가 기능을 열면 여기서 true 로 바꾸는 것이 "mock 해제" 다.
 *
 * 조회와 처리(목록과 그 승인 버튼 등)는 같은 키로 묶는다. 목록은 mock 인데 처리만
 * 실서버로 가면 없는 id 로 요청하게 된다.
 */
const BE_READY = {
  /** 아이 명부 조회 (GET /institutions/me/children) */
  children: true,
  /** 아이 등록 + 첫 초대 코드 (POST /institutions/me/children) */
  childRegister: true,
  /** 초대 코드 추가 발급 · 목록 · 취소 (O-15 ~ O-17) */
  invitations: true,
  /** 아이와 연결된 보호자 목록 · 기관이 연결 해제 (O-18, O-19) */
  childGuardians: true,
  /** 아동 상세의 기록 타임라인 — 우리 기관이 승인한 요약 (O-14) */
  childTimeline: true,
  /** 확인 필요 큐 조회 · 처리 (O-22, O-23) */
  matchingQueue: true,
  /** 수정 요청(BLOCK) 큐 조회 · 처리 (O-24, O-25) */
  blockedQueue: true,
  /**
   * 요약 검토 조회 · 문장 카드 승인 · 보류 · 제거 · 보류 해제 (O-30, O-31, O-33).
   * 문장 카드(#231)가 있는 서버여야 한다 — 그 전 서버는 cards 를 조용히 버리고 AI 원문을 승인한다.
   * 요약은 AI 확인이 끝나면 자동으로 만들어진다 (#369).
   */
  gate1: true,
  /** 대시보드 진행 현황 · 오늘 AI가 한 일 · AI 상태 (O-80 ~ O-82) */
  dashboard: true,
  /** 요약 실패 목록 · 다시 요약 · 요약 없이 마무리 (O-35 ~ O-37, #369) */
  summaryFailures: true,
  /** 인사이트 조회 · 2차 공유 (O-40 ~ O-42) */
  insights: true,
  /** 원본 업로드 (POST /raw-records) */
  upload: true,
  /** 파일별 처리 현황 (O-26) */
  fileProgress: true,
  /** 기관 수신함 목록 · 단건 (O-50 · O-51). 단건을 열면 처음 열람 시각이 남는다 */
  inbox: true,
  /** 내 기관 정보 — 아동 명부의 "우리 기관 코드" 카드 (O-01) */
  institutionInfo: true,
  /** 보호자가 기관 코드로 보낸 연결 요청 목록 · 수락 · 거절 (O-60 ~ O-62) */
  linkRequests: true,
  /** 보호자: 초대 코드 수락 뒤 동의 화면(P-02)의 미리보기 · 동의 (G-02, G-41) */
  guardianConsent: true,
  /**
   * 보호자: 설정 화면의 동의 끄기 (G-41 revoke). 다시 켤 때는 동의 화면(P-02, guardianConsent)을
   * 거친다.
   */
  guardianLinks: true,
  /** 보호자: 초대 코드 수락 (G-04) */
  guardianInvite: true,
  /**
   * 보호자: 아이 연결 해제 (G-05). 해제할 아이는 아이 목록(guardianChildren)에서 고른다 — 목록이
   * mock 이면 서버에 없는 아이 id 로 요청하게 되니 두 키는 함께 켜고 끈다.
   */
  guardianUnlink: true,
  /**
   * 보호자: 연결된 아이 목록 · 홈 데이터 (G-10, G-11). 보호자 화면 입구(parent/guard.tsx)도
   * 이 키를 본다 — 꺼져 있으면 mock 아이 대신 "준비 중"을 띄운다.
   */
  guardianChildren: true,
  /** 보호자: 일지 목록 · 상세 (G-20, G-21). 타임라인 · 일지 상세 · 홈 "새 소식". 아이 목록·홈과는 따로 켠다 */
  guardianJournal: true,
  /**
   * 보호자: 대기 중 기관 권한 요청 (G-40). 알림 화면 · 홈 요청 배너 · 헤더 알림 점이 이 목록 하나를 본다.
   * 요청 거절(G-42)은 MVP 에서 뺐다 — 알림 화면은 동의 화면으로 보내는 버튼 하나만 둔다.
   */
  consentRequests: true,
  /**
   * 준비물·확인사항 — 기관의 보내기 · 보낸 목록 · 내리기(O-70 ~ O-72)와 보호자의 목록 · 확인(G-50, G-51).
   * 보호자 쪽은 P-12 · 설정 개수 · 알림 화면 · 헤더 알림 점이 본다.
   */
  checklists: true,
  /**
   * 보호자: 기관 코드 조회 · 연결 요청 (G-43, G-44). 바로 연결되지 않고 기관이 수락해야 명부에 오른다.
   * 수락 대기 중 기관은 아이 목록·홈 응답(guardianChildren)의 linkRequests 로 온다.
   */
  institutionCode: true,
  /**
   * 보호자: 받은 인사이트 목록 · 단건 (G-35 · G-36). 리포트 탭 · 리포트 상세 · 하단 탭의 "안 본 리포트" 점.
   * 단건을 열면 처음 열람 시각이 남는다
   */
  guardianInsights: true,
  /**
   * 보호자: 기관 공유 내역 (G-45). 설정 기관 줄의 "받은 리포트 N편" 과 기관 공유 내역 화면. 거둔(가려진) 공유도 온다
   */
  institutionShares: true,
  /** 보호자: 돌봄 정보 저장 (G-13). 알레르기·복용약. 연결된 보호자끼리 함께 보고 고친다. 기관에는 공유하지 않는다 */
  careInfo: true,
} satisfies Record<string, boolean>;

export type Feature = keyof typeof BE_READY;

/** 이 기능을 mock 으로 처리할지. 전체 mock 이거나 백엔드에 아직 없으면 true. */
function mockFor(feature: Feature): boolean {
  return USE_MOCK || !BE_READY[feature];
}

/**
 * 여러 기능의 숫자를 한데 모아 보여주는 곳(대시보드·사이드바 배지)용.
 *
 * 실연동 빌드에서 백엔드에 아직 없는 기능은 mock 대신 null 을 돌려준다. 모아 보는
 * 화면에 실데이터와 mock 이 표시 없이 섞이면 무엇이 실제로 연결됐는지 알 수 없어서다.
 * 화면은 null 을 "준비 중"으로 그린다. 전체 mock 모드에서는 평소대로 mock 을 돌려준다.
 */
export function loadIfReady<T>(feature: Feature, load: () => Promise<T>): Promise<T | null> {
  return isReady(feature) ? load() : Promise.resolve(null);
}

/**
 * 이 기능의 화면을 그대로 보여도 되는지. 전체 mock 이거나 백엔드에 있으면 true.
 * false 면 화면은 mock 데이터 대신 "준비 중"을 보인다(components/org/NotReady).
 */
export function isReady(feature: Feature): boolean {
  return USE_MOCK || BE_READY[feature];
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  return (await requestWithHeaders<T>(path, init)).data;
}

/** 응답 헤더까지 돌려준다. 아이 목록의 `X-Itda-User-Id`(그 목록을 가져온 계정)를 읽을 때 쓴다. */
async function requestWithHeaders<T>(path: string, init?: RequestInit): Promise<{ data: T; headers: Headers }> {
  /*
   * 출입증은 httpOnly 쿠키라 **브라우저가 자동으로** 붙인다. 헤더를 직접 만들지 않는다.
   * credentials 를 명시하는 이유는 VITE_API_BASE_URL 로 다른 오리진을 볼 때도 쿠키가
   * 실리게 하기 위해서다(백엔드 CORS 가 allowCredentials 로 열려 있다).
   *
   * 대신 쿠키가 자동 전송되므로 쓰기 요청에는 CSRF 토큰이 필요하다 — 없으면 403 이다.
   */
  const method = (init?.method ?? "GET").toUpperCase();
  const needsCsrf = method !== "GET" && method !== "HEAD";
  // 파일 업로드는 브라우저가 boundary 를 붙인 multipart content-type 을 직접 만들어야 한다
  const isForm = init?.body instanceof FormData;

  const res = await fetch(`${BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      ...(isForm ? {} : { "content-type": "application/json" }),
      ...(needsCsrf ? csrfHeader() : {}),
      ...(init?.headers ?? {}),
    },
    cache: "no-store",
  });
  if (!res.ok) {
    // 쿠키가 만료·무효면 로컬 세션 표시도 지운다.
    // 안 그러면 로그인된 척하면서 요청마다 401 만 맞는 상태가 된다.
    if (res.status === 401) clearSession();
    // BE 공통 에러 형식 { result, code, message } — global/exception/ErrorResponse.java
    const body = await res.json().catch(() => ({}));
    const error = new ApiError(res.status, body.code ?? "UNKNOWN", body.message);
    if (error.status === 403 && error.code === "TERMS_NOT_AGREED") void sendToTerms();
    throw error;
  }
  // 204 No Content 에는 래퍼가 없다(api-conventions.md). 백엔드에 아직 204 를 주는
  // 곳은 없지만 규약이라 미리 받아둔다.
  if (res.status === 204) return { data: undefined as T, headers: res.headers };

  // 성공 응답은 { result, data, message } 로 감싸여 온다. 화면이 쓰는 건 data 뿐이라
  // 여기서 벗겨서 돌려준다 — 화면 코드는 래퍼를 모른다.
  // 데이터가 없는 정상 처리는 data 가 생략될 수 있어 undefined 가 된다.
  const body = await res.json();
  return { data: body.data as T, headers: res.headers };
}

export { ApiError };

/**
 * 약관에 동의하지 않은 회원은 서버가 보호 API 를 모두 403 TERMS_NOT_AGREED 로 막는다. 화면마다 처리하지 않고
 * 여기서 한 번 그 역할의 약관 화면으로 보낸다 — 앱을 열어 둔 사이 약관 판이 올라간 경우 등.
 *
 * 기관 화면 주소에는 앞머리가 없어 주소로는 역할을 알 수 없다. 그래서 세션(`/auth/me`, 약관 전에도 열려 있다)을
 * 다시 읽는다. 기관 → `/org-terms`, 보호자 → `/parent/invite`(초대 화면이 이 오류를 받아 약관 단계로 돌린다),
 * 로그인이 풀렸으면 `/login`. 세션 조회가 실패하면 어디로도 보내지 않는다 — 원래 요청의 오류는 그대로 던져져
 * 화면의 오류 안내 · 다시 시도가 돈다. 짐작으로 보호자 화면에 보내면 기관이 거기 갇힌다.
 * 이미 그 화면이면 옮기지 않는다.
 */
async function sendToTerms(): Promise<void> {
  if (typeof window === "undefined") return;
  let target: string;
  try {
    const session = await getSession();
    if (!session.loggedIn) target = "/login";
    else if (session.role === "org") target = "/org-terms";
    else target = "/parent/invite";
  } catch {
    return;
  }
  if (window.location.pathname !== target) window.location.replace(target);
}

/* ── 기관 ───────────────────────────────────────────── */

/**
 * mock 아이를 우리 기관 명부 한 줄로 만든다. 서버 명부(O-10)처럼 우리 기관에 다니는 아이만,
 * 우리 기관 기준 동의(consent)를 붙여서. 다른 기관에만 다니는 mock 아이(연결 요청 데모)는 빠진다.
 */
function rosterItemOf(child: Child): ChildListItem | null {
  const mine = child.institutions.find((i) => i.institution.id === mock.MY_INSTITUTION.id);
  if (!mine) return null;
  // 명부에 있는 칸만 새 객체로. 타입만 좁히면 학교 · 다른 기관 · 돌봄 정보가 런타임에 그대로 실린다.
  // 원본 mock 은 보호자 화면이 쓰므로 고치지 않는다.
  const { id, name, birthDate, externalId, status } = child;
  return { id, name, birthDate, externalId, status, consent: mine.consent };
}

/** GET /api/v1/institutions/me/children */
export async function getChildren(): Promise<ChildListItem[]> {
  if (mockFor("children")) {
    return mock.CHILDREN.map(rosterItemOf).filter((c): c is ChildListItem => c !== null);
  }
  return request("/api/v1/institutions/me/children");
}

/**
 * 아이 한 명 — 언제나 명부(O-10)에서 찾는다. 우리 명부에 없으면 undefined(화면은 404).
 * 아동 상세 API(O-13)는 만들지 않는다: 명부에 이름 · 생년월일 · 관리번호 · 우리 기관 동의가 다 있고, 다른 기관 정보는
 * 기관 화면에 보이지 않는다 (기관 경계).
 */
export async function getChild(id: string): Promise<ChildListItem | undefined> {
  return (await getChildren()).find((c) => c.id === id);
}

/** GET /api/v1/institutions/me — 내 기관 정보와 보호자에게 알려 줄 기관 코드 (O-01) */
export async function getMyInstitution(): Promise<MyInstitution> {
  if (mockFor("institutionInfo")) return { ...mock.MY_INSTITUTION, code: mock.MY_INSTITUTION_CODE };
  return request("/api/v1/institutions/me");
}

/** GET /api/v1/institutions/me/link-requests — 보호자가 기관 코드로 보낸, 기다리는 연결 요청 (O-60) */
export async function getLinkRequests(): Promise<PendingLinkRequest[]> {
  if (mockFor("linkRequests")) return mock.LINK_REQUESTS;
  return request("/api/v1/institutions/me/link-requests");
}

/**
 * POST /api/v1/institutions/me/link-requests/{childId}/accept — 연결 요청 수락 (O-61).
 *
 * 관리번호는 아이 등록과 같은 규칙이다(필수, 같은 기관 안 중복이면 409 DUPLICATE_EXTERNAL_ID).
 * 수락은 동의가 아니라서 돌아오는 명부 한 줄의 consent 는 not_granted 다. 기다리는 요청이 없으면
 * 404 LINK_REQUEST_NOT_FOUND — 그사이 보호자가 연결을 끊었거나 다른 화면에서 처리됐다.
 */
export async function acceptLinkRequest(childId: string, externalId: string): Promise<ChildListItem> {
  if (mockFor("linkRequests")) {
    const at = mock.LINK_REQUESTS.findIndex((r) => r.child.id === childId);
    const child = mock.CHILDREN.find((c) => c.id === childId);
    if (at === -1 || !child) throw new ApiError(404, "LINK_REQUEST_NOT_FOUND");
    const trimmed = externalId.trim();
    if (mock.CHILDREN.some((c) => c.externalId === trimmed)) {
      throw new ApiError(409, "DUPLICATE_EXTERNAL_ID");
    }
    mock.LINK_REQUESTS.splice(at, 1);
    child.externalId = trimmed;
    child.institutions.push({ institution: mock.MY_INSTITUTION, consent: "not_granted" });
    return rosterItemOf(child)!;
  }
  return request(`/api/v1/institutions/me/link-requests/${encodeURIComponent(childId)}/accept`, {
    method: "POST",
    body: JSON.stringify({ externalId }),
  });
}

/** POST /api/v1/institutions/me/link-requests/{childId}/reject — 연결 요청 거절 (O-62). 보호자에게 알리지 않는다 */
export async function rejectLinkRequest(childId: string): Promise<void> {
  if (mockFor("linkRequests")) {
    const at = mock.LINK_REQUESTS.findIndex((r) => r.child.id === childId);
    if (at === -1) throw new ApiError(404, "LINK_REQUEST_NOT_FOUND");
    mock.LINK_REQUESTS.splice(at, 1);
    return;
  }
  return request(`/api/v1/institutions/me/link-requests/${encodeURIComponent(childId)}/reject`, {
    method: "POST",
  });
}

/**
 * GET /api/v1/institutions/me/children/{childId}/timeline — 우리 기관이 요약 검토에서 승인한 요약, 관찰 날짜 최근 순
 * (같은 날은 승인 최근 순). 우리 명부에 없는 아이는 404 CHILD_NOT_FOUND
 */
export async function getTimeline(childId: string): Promise<TimelineEntry[]> {
  if (mockFor("childTimeline")) return mock.TIMELINE[childId] ?? [];
  return request(`/api/v1/institutions/me/children/${encodeURIComponent(childId)}/timeline`);
}

/**
 * GET /api/v1/matching-queue — **계약 확정, #35 merge 대기** (api-spec.md O-22)
 *
 * institutionId 는 서버가 인증 정보에서 가져가므로 보내지 않는다.
 * 서버는 근거 구간이 없으면 evidence 를 null 로 보낸다 — 화면이 배열만 다루도록 여기서 편다.
 */
export async function getMatchingQueue(): Promise<MatchingItem[]> {
  if (mockFor("matchingQueue")) return mock.MATCHING_QUEUE;
  const items = await request<MatchingItem[]>("/api/v1/matching-queue");
  return items.map((item) => ({
    ...item,
    record: { ...item.record, fullContent: item.record.fullContent ?? null },
    matchedChildId: item.matchedChildId ?? null,
    candidates: item.candidates ?? [],
    evidence: item.evidence ?? [],
  }));
}

/** GET /api/v1/validation-results?status=BLOCK (api-spec O-24) */
export async function getBlockedQueue(): Promise<BlockedItem[]> {
  if (mockFor("blockedQueue")) return mock.BLOCKED_QUEUE;
  const items = await request<BlockedItem[]>("/api/v1/validation-results?status=BLOCK");
  return items.map((item) => ({
    ...item,
    record: {
      ...item.record,
      capturedAt: item.record.capturedAt ?? null,
      preview: item.record.preview ?? null,
      fullContent: item.record.fullContent ?? null,
    },
    childName: item.childName ?? null,
  }));
}

/** BE Gate1SummaryResponse 그대로. id 가 숫자이고 상태 이름이 다르다 */
interface Gate1SummaryRaw {
  id: number;
  childId: number;
  childName: string;
  entryDate: string;
  revision: number;
  status: "GATE1_PENDING" | "HOLD";
  version: string;
  processing: boolean;
  content: string;
  claims: { index: number; text: string; evidence?: { journalEntryId: number; quote: string }[] | null }[] | null;
  noContent: boolean;
  needsReview: boolean;
  reviewReasons: string[] | null;
  uncoveredEntries: { journalEntryId: number; content: string }[] | null;
  /** claimIndexes 는 예전 서버가 주지 않는다 — 그때는 배너만 보인다 */
  reviewFlags: { journalEntryId: number; issueTypes: string[]; reasons?: string[]; claimIndexes?: number[] | null }[] | null;
  /** 예전 서버는 주지 않는다 — 그때는 요약에 안 들어간 일지만 원본으로 보인다 */
  entries?: { journalEntryId: number; content: string }[] | null;
}

function toSummaryItem(raw: Gate1SummaryRaw): SummaryItem {
  return {
    id: String(raw.id),
    childId: String(raw.childId),
    childName: raw.childName,
    entryDate: raw.entryDate,
    revision: raw.revision,
    gate1Status: raw.status === "HOLD" ? "hold" : "pending",
    version: raw.version,
    processing: raw.processing,
    content: raw.content,
    claims: (raw.claims ?? []).map((c) => ({
      index: c.index,
      text: c.text,
      evidence: (c.evidence ?? []).map((e) => ({ journalEntryId: String(e.journalEntryId), quote: e.quote })),
    })),
    noContent: raw.noContent ?? false,
    needsReview: raw.needsReview,
    reviewReasons: raw.reviewReasons ?? [],
    uncoveredEntries: (raw.uncoveredEntries ?? []).map((e) => ({
      journalEntryId: String(e.journalEntryId),
      content: e.content,
    })),
    entries: (raw.entries ?? raw.uncoveredEntries ?? []).map((e) => ({
      journalEntryId: String(e.journalEntryId),
      content: e.content,
    })),
    reviewFlags: (raw.reviewFlags ?? []).map((f) => ({
      journalEntryId: String(f.journalEntryId),
      issueTypes: f.issueTypes ?? [],
      // reasons 가 없는 예전 서버면 코드라도 보여 준다 — 빈칸보다 낫다. 빈 값은 버린다(안내가 " · " 에서 끊기지 않게)
      reasons: (f.reasons ?? f.issueTypes ?? []).filter((r) => r.trim() !== ""),
      claimIndexes: f.claimIndexes ?? [],
    })),
  };
}

/**
 * GET /api/v1/summaries?gate1Status=pending|hold (O-30)
 * 검토 대기와 보류함은 따로 묻는다. 오래된 날짜부터 온다.
 */
export async function getGate1Queue(
  gate1Status: "pending" | "hold" = "pending",
): Promise<SummaryItem[]> {
  if (mockFor("gate1")) return mock.GATE1_QUEUE.filter((s) => s.gate1Status === gate1Status);
  const items = await request<Gate1SummaryRaw[]>(`/api/v1/summaries?gate1Status=${gate1Status}`);
  return items.map(toSummaryItem);
}

type Gate1Decision = "approve" | "hold" | "remove" | "confirm" | "revert";

const GATE1_STATUS_AFTER = {
  approve: "approved",
  hold: "hold",
  remove: "removed",
  confirm: "confirmed",
  revert: "reverted",
} as const satisfies Record<Gate1Decision, Gate1Status>;

/** mock 에서 BE 와 같은 400 · 409 를 흉내 낸다 — 화면의 오류 안내를 mock 으로도 볼 수 있게 */
function mockGate1Guard(item: SummaryItem | undefined, decision: string): SummaryItem {
  if (!item) throw new ApiError(404, "SUMMARY_NOT_FOUND", "요약을 찾을 수 없습니다.");
  if (item.gate1Status !== "pending" && item.gate1Status !== "hold") {
    throw new ApiError(409, "SUMMARY_ALREADY_REVIEWED", "이미 처리된 요약입니다.");
  }
  const forNoContent = decision === "confirm" || decision === "revert";
  if (item.noContent !== forNoContent && decision !== "reopen") {
    throw new ApiError(400, "SUMMARY_INVALID_REQUEST", "이 요약에 맞지 않는 결정입니다.");
  }
  if (item.processing) {
    throw new ApiError(409, "SUMMARY_REGENERATING", "요약을 다시 만드는 중입니다.");
  }
  if (item.gate1Status === "hold" && decision !== "remove" && decision !== "reopen") {
    throw new ApiError(409, "SUMMARY_HELD", "보류된 요약입니다.");
  }
  return item;
}

/**
 * POST /api/v1/summaries/{summaryId}/gate1 (O-31)
 *
 * cards 는 승인할 때만, 고친 것이 있을 때만 보낸다 — 없으면 AI 요약 그대로 승인이다.
 * 보류 · 제거에 보내면 400 이다. version 은 목록에서 받은 값 그대로 — 그 사이 요약이 새 기록으로
 * 바뀌었으면 409 SUMMARY_CHANGED 다. 보류한 요약은 제거만 받는다(승인 · 보류는 409 SUMMARY_HELD).
 * 요약할 내용 없음(noContent) 요약은 confirm · revert 만, 그 밖의 요약은 그 둘을 빼고 받는다 (어긋나면 400).
 */
export async function decideGate1(
  summaryId: string,
  body: { decision: Gate1Decision; cards?: Gate1Card[]; version: string },
  /** 페이지를 떠나는 중에 보낼 때(되돌리기 대기 중이던 승인) — 브라우저가 요청을 끊지 않게 */
  opts?: { keepalive?: boolean },
): Promise<{ summaryId: string }> {
  if (mockFor("gate1")) {
    const item = mockGate1Guard(
      mock.GATE1_QUEUE.find((s) => s.id === summaryId),
      body.decision,
    );
    item.gate1Status = GATE1_STATUS_AFTER[body.decision];
    return { summaryId };
  }
  const res = await request<{ summaryId: number }>(
    `/api/v1/summaries/${encodeURIComponent(summaryId)}/gate1`,
    { method: "POST", body: JSON.stringify(body), keepalive: opts?.keepalive },
  );
  return { summaryId: String(res.summaryId) };
}

/**
 * POST /api/v1/summaries/{summaryId}/reopen (O-33) · 보류 해제
 * 보류함의 요약을 AI 요약 그대로 검토 대기로 돌린다. 본문은 없다.
 */
export async function reopenGate1(summaryId: string): Promise<{ summaryId: string }> {
  if (mockFor("gate1")) {
    const item = mockGate1Guard(mock.GATE1_QUEUE.find((s) => s.id === summaryId), "reopen");
    if (item.gate1Status !== "hold") {
      throw new ApiError(409, "SUMMARY_NOT_HELD", "보류된 요약이 아닙니다.");
    }
    item.gate1Status = "pending";
    return { summaryId };
  }
  const res = await request<{ summaryId: number }>(
    `/api/v1/summaries/${encodeURIComponent(summaryId)}/reopen`,
    { method: "POST" },
  );
  return { summaryId: String(res.summaryId) };
}

/* ── 대시보드 (O-80 ~ O-82) ──────────────────── */

/** BE 응답 그대로. childId · target.id 가 숫자로 올 수 있다 */
interface PipelineRaw {
  turn: Pipeline["turn"];
  rows: (Omit<PipelineRow, "childId" | "target"> & {
    childId: string | number | null;
    target: { type: NonNullable<PipelineRow["target"]>["type"]; id?: string | number | null } | null;
  })[];
}

/** GET /api/v1/dashboard/pipeline (O-80) — 선생님 차례 건수와 진행 현황 줄. 단계 판정은 서버가 한다 */
export async function getPipeline(): Promise<Pipeline> {
  if (mockFor("dashboard")) {
    const p = mock.PIPELINE;
    return { turn: { ...p.turn }, rows: p.rows.map((r) => ({ ...r })) };
  }
  const raw = await request<PipelineRaw>("/api/v1/dashboard/pipeline");
  return {
    turn: raw.turn,
    rows: raw.rows.map((r) => ({
      ...r,
      childId: r.childId == null ? null : String(r.childId),
      target: r.target
        ? { type: r.target.type, ...(r.target.id != null ? { id: String(r.target.id) } : {}) }
        : null,
    })),
  };
}

/** GET /api/v1/dashboard/ai-activity (O-81) — 서울 기준 오늘 0시부터 AI 가 한 일 */
export async function getAiActivity(): Promise<AiActivity> {
  if (mockFor("dashboard")) return { ...mock.AI_ACTIVITY };
  return request<AiActivity>("/api/v1/dashboard/ai-activity");
}

/** GET /api/v1/system/ai-status (O-82) — AI 장애 배너용 */
export async function getAiStatus(): Promise<AiStatus> {
  if (mockFor("dashboard")) return { ...mock.AI_STATUS };
  return request<AiStatus>("/api/v1/system/ai-status");
}

/**
 * 진행 현황 AI 줄의 지금 단계 (O-83, #454). 화면이 1초마다 부른다.
 * 실패(404 · 5xx)는 던진다 — 부르는 쪽(lib/aiProgress)이 enabled: false 와 똑같이 다룬다
 */
export async function getAiProgress(): Promise<AiProgress> {
  if (mockFor("dashboard")) return mock.mockAiProgress();
  const raw = await request<AiProgress>("/api/v1/dashboard/ai-progress");
  return {
    enabled: raw.enabled === true,
    items: (raw.items ?? []).map((i) => ({ ...i, childId: i.childId == null ? null : String(i.childId) })),
  };
}

/* ── 요약 실패 (O-35 ~ O-37) ──────────────────── */

interface FailedSummaryRaw {
  childId: number;
  childName: string;
  entryDate: string;
  entries: { journalEntryId: number; content: string }[] | null;
  reason: string;
}

/** GET /api/v1/summaries/failed (O-35) — 재시도 뒤에도 요약하지 못한 묶음. 오래된 날짜부터 */
export async function getFailedSummaries(): Promise<FailedSummary[]> {
  if (mockFor("summaryFailures")) return mock.FAILED_SUMMARIES.map((f) => ({ ...f }));
  const raw = await request<FailedSummaryRaw[]>("/api/v1/summaries/failed");
  return raw.map((f) => ({
    ...f,
    childId: String(f.childId),
    entries: (f.entries ?? []).map((e) => ({ journalEntryId: String(e.journalEntryId), content: e.content })),
  }));
}

function failedBody(childId: string, entryDate: string): string {
  return JSON.stringify({ childId: Number(childId), entryDate });
}

function dropMockFailed(childId: string, entryDate: string) {
  const at = mock.FAILED_SUMMARIES.findIndex((f) => f.childId === childId && f.entryDate === entryDate);
  if (at < 0) throw new ApiError(404, "SUMMARY_FAILED_NOT_FOUND", "요약 실패 기록이 없습니다.");
  mock.FAILED_SUMMARIES.splice(at, 1);
}

/** POST /api/v1/summaries/failed/retry (O-36) — 다시 요약. 워커가 곧 요약하고 결과는 O-30 에 온다 */
export async function retryFailedSummary(childId: string, entryDate: string): Promise<void> {
  if (mockFor("summaryFailures")) return dropMockFailed(childId, entryDate);
  await request("/api/v1/summaries/failed/retry", { method: "POST", body: failedBody(childId, entryDate) });
}

/** POST /api/v1/summaries/failed/close (O-37) — 요약 없이 마무리. 되돌릴 수 없다 */
export async function closeFailedSummary(childId: string, entryDate: string): Promise<void> {
  if (mockFor("summaryFailures")) return dropMockFailed(childId, entryDate);
  await request("/api/v1/summaries/failed/close", { method: "POST", body: failedBody(childId, entryDate) });
}

/**
 * GET /api/v1/insights?gate2Status= (O-40)
 *
 * BE 는 우리 기관이 승인 주체인 것만 준다 — 화면에서 기관으로 다시 거르지 않는다.
 * 상태를 안 보내면 BE 기본값이 pending 이라 승인·보류된 것은 따로 물어야 한다.
 */
export async function getInsights(gate2Status: Gate2Status = "pending"): Promise<Insight[]> {
  if (mockFor("insights")) {
    return mock.INSIGHTS.filter(
      (i) => i.primarySource.id === mock.MY_INSTITUTION.id && i.gate2Status === gate2Status,
    );
  }
  return request(`/api/v1/insights?gate2Status=${gate2Status}`);
}

/**
 * POST /api/v1/insights/{insightId}/gate2 (O-42)
 *
 * 수신 기관은 보내지 않는다 — 승인하면 보호자와, targets 중 동의가 granted 인 기관에 열람 권한이
 * 생긴다 (#233). 고르는 단계는 없다. 승인 · 보류 모두 되돌릴 수 없다.
 * 재료 기관의 동의가 그사이 꺼졌으면 승인만 409 INSIGHT_MATERIAL_CONSENT_REVOKED (보류는 된다).
 */
export async function decideGate2(insightId: string, body: { decision: "approve" | "hold" }) {
  if (mockFor("insights")) {
    const item = mock.INSIGHTS.find((i) => i.id === insightId);
    if (item) item.gate2Status = body.decision === "approve" ? "approved" : "hold";
    return { insightId, gate2Status: item?.gate2Status ?? "pending" };
  }
  return request(`/api/v1/insights/${insightId}/gate2`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/**
 * 확인 필요 큐에서 아이를 확정(또는 "우리 기관 아동 아님"으로 제외)하면 큐에서 빠진다.
 *
 * POST /api/v1/matching-queue/{id}/resolve — **계약 확정, #35 merge 대기** (api-spec.md O-23)
 *    { action: "assign", childId } 또는 { action: "not_ours" }. childId 는 assign 에만 보낸다.
 */
export async function resolveMatchingItem(
  id: string,
  resolution: MatchResolution,
): Promise<{ ok: true }> {
  if (mockFor("matchingQueue")) {
    const idx = mock.MATCHING_QUEUE.findIndex((m) => m.id === id);
    if (idx !== -1) mock.MATCHING_QUEUE.splice(idx, 1);
    return { ok: true };
  }
  return request(`/api/v1/matching-queue/${id}/resolve`, {
    method: "POST",
    body: JSON.stringify(resolution),
  });
}

/* ── 업로드 · 처리 현황 ─────────────────────────────── */

/**
 * POST /api/v1/raw-records — **구현됨** (multipart: file → 201)
 *
 * 즉시 응답하고 매칭 · 검증 · 요약은 백그라운드에서 돈다. 파일마다 한 번씩 부른다.
 * 응답은 RawRecordResponse(backend/.../dto/RawRecordResponse.java) 다.
 *
 * 한 파일이 실패해도 나머지는 이미 서버에 저장됐다. 그래서 전체를 실패로 던지지 않고
 * 파일마다 결과를 돌려준다 — 화면이 실패한 것만 다시 올리게 해야 원본이 중복되지 않는다.
 */
export async function uploadRawRecords(files: File[]): Promise<UploadResult[]> {
  if (mockFor("upload")) {
    // 실제 업로드처럼 잠깐 걸리게 한다 — 바로 끝나면 "올리고 있어요" 화면을 mock 에서 볼 수 없다
    await new Promise((resolve) => setTimeout(resolve, 1500));
    const now = new Date().toISOString();
    const results = files.map((file, i): UploadResult => {
      const reason = mockRejectReason(file);
      if (reason) return { ok: false, file, reason };
      return {
        ok: true,
        file,
        progress: {
          rawRecordId: `rr_mock_${Date.now()}_${i}`,
          fileName: file.name,
          uploadedAt: now,
          entries: [],
        },
      };
    });
    const created = results.flatMap((r) => (r.ok ? [r.progress] : []));
    for (const file of created) simulated.set(file.rawRecordId, Date.now());
    mock.FILE_PROGRESS.unshift(...created);
    return results;
  }
  return mapInBatches(files, UPLOAD_CONCURRENCY, async (file): Promise<UploadResult> => {
    // BE 도 400 으로 거절하지만, 보내기 전에 걸러 20MB 짜리 hwp 같은 걸 끝까지 올리지 않게 한다
    if (!hasUploadExtension(file)) return { ok: false, file, reason: "invalid" };
    try {
      return { ok: true, file, progress: await uploadOne(file) };
    } catch (err) {
      return { ok: false, file, reason: uploadFailReasonOf(err) };
    }
  });
}

/**
 * 한 번에 보내는 파일 수. 서버가 업로드의 글자 뽑기를 서버 전체에서 동시에 몇 개까지만 하고(#424, 기본 2개 · 기다리는 4개),
 * 넘치면 429 RAW_RECORD_UPLOAD_BUSY 로 거절한다. 한꺼번에 보내면 파일을 여러 개 고른 선생님이 그 상한에 걸린다.
 */
const UPLOAD_CONCURRENCY = 2;

/** items 를 limit 개씩 동시에 처리한다. 결과는 items 순서 그대로다. */
async function mapInBatches<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

async function uploadOne(file: File): Promise<FileProgress> {
  const form = new FormData();
  form.append("file", file);
  const saved = await request<{ id: number; originalFilename: string; createdAt: string }>(
    "/api/v1/raw-records",
    { method: "POST", body: form },
  );
  return {
    rawRecordId: String(saved.id),
    fileName: saved.originalFilename,
    uploadedAt: saved.createdAt,
    entries: [],
  };
}

/**
 * 서버가 이유를 코드로 준 거절(#424)을 먼저 가르고, 나머지는 상태 코드로 가른다. nginx 가 25MB 에서 먼저 막으면 본문이
 * JSON 이 아니라 code 가 없다. 네트워크 오류(fetch 의 TypeError)도 temporary 다.
 */
function uploadFailReasonOf(err: unknown): UploadFailReason {
  if (err instanceof ApiError && err.code === "RAW_RECORD_ENTRY_LIMIT_EXCEEDED") return "too_many_entries";
  if (isAgentQueueFull(err)) return "queue_full";
  if (err instanceof ApiError && err.code === "RAW_RECORD_UPLOAD_BUSY") return "server_busy";
  if (err instanceof ApiError && err.status === 400) return "invalid";
  if (err instanceof ApiError && err.status === 413) return "too_large";
  return "temporary";
}

/**
 * 올릴 수 있는 형식. BE RawRecordService.ALLOWED_EXTENSIONS 와 같게 둔다 (docx · pdf 글자 뽑기 #383).
 * 스캔하거나 사진으로 만든 pdf 는 글자가 없어 업로드는 되지만 기록이 나뉘지 않는다(entries: []).
 */
export const UPLOAD_EXTENSIONS = ["csv", "txt", "docx", "pdf"];
/** mock 도 BE 와 같은 용량 제한으로 거절한다 — 실패 화면을 mock 에서 확인할 수 있게 */
const UPLOAD_MAX_BYTES = 20 * 1024 * 1024;

function hasUploadExtension(file: File): boolean {
  const ext = file.name.split(".").pop()?.toLowerCase() ?? "";
  return UPLOAD_EXTENSIONS.includes(ext);
}

/** 파일명에 "많음" · "대기" · "혼잡" 이 들어가면 그 거절을 흉내낸다 — 실패 문구를 mock 에서 확인할 수 있게 (#424) */
function mockRejectReason(file: File): UploadFailReason | null {
  if (!hasUploadExtension(file)) return "invalid";
  if (file.size > UPLOAD_MAX_BYTES) return "too_large";
  if (file.name.includes("많음")) return "too_many_entries";
  if (file.name.includes("대기")) return "queue_full";
  if (file.name.includes("혼잡")) return "server_busy";
  return null;
}

/**
 * 서버의 그 기관 AI 처리 줄이 꽉 찼다 (429 AGENT_QUEUE_FULL, #424). 업로드와, 기록을 AI 로 다시 보내는 선생님 처리
 * (확인 필요 큐의 아이 고르기 · 막힌 본문 고치기 · 요약 실패 [다시 요약] · "요약할 내용 없음" 되돌리기)가 받는다.
 */
export function isAgentQueueFull(err: unknown): boolean {
  return err instanceof ApiError && err.code === "AGENT_QUEUE_FULL";
}

/** isAgentQueueFull 일 때 선생님 처리 화면이 보이는 문구 */
export const AGENT_QUEUE_FULL_TEXT = "앞서 올린 기록을 처리하고 있어요. 끝나면 다시 해 주세요.";

/**
 * 파일별 처리 현황. 최근 업로드가 먼저 온다.
 *
 * GET /api/v1/raw-records/progress — 구현됨 (api-spec.md O-26)
 *    파일을 기록 단위로 나누기 전까지는 entries: [] 만 와서 "기록 등록 중"에 머문다. 정상이다.
 */
export async function getFileProgress(): Promise<FileProgress[]> {
  if (mockFor("fileProgress")) {
    tickSimulation();
    return mock.FILE_PROGRESS;
  }
  return request("/api/v1/raw-records/progress");
}

/**
 * mock 전용: 방금 올린 파일이 백그라운드에서 한 칸씩 나아가는 것을 흉내낸다.
 * 조회할 때마다 마지막으로 움직인 뒤 일정 시간이 지났으면 한 단계씩 전진시킨다.
 */
const simulated = new Map<string, number>();
const TICK_MS = 1200;

function tickSimulation() {
  const now = Date.now();
  for (const [id, last] of simulated) {
    if (now - last < TICK_MS) continue;
    const file = mock.FILE_PROGRESS.find((f) => f.rawRecordId === id);
    if (!file) {
      simulated.delete(id);
      continue;
    }
    simulated.set(id, now);

    // 첫 단계: 파일에서 기록을 떼어낸다
    if (file.entries.length === 0) {
      file.entries = Array.from({ length: 3 }, (_, i) => ({
        id: `${id}_${i + 1}`,
        stageIndex: MATCHING_INDEX,
        state: "running" as const,
      }));
      continue;
    }

    for (const [i, e] of file.entries.entries()) {
      if (e.state !== "running") continue;
      // 데모: 두 번째 기록은 매칭에서 사람 확인으로 멈춘다
      if (e.stageIndex === MATCHING_INDEX && i === 1) {
        e.state = "waiting";
        continue;
      }
      e.stageIndex += 1;
      if (e.stageIndex >= GATE1_INDEX) {
        e.stageIndex = GATE1_INDEX;
        e.state = "waiting";
      }
    }
    if (!file.entries.some((e) => e.state === "running")) simulated.delete(id);
  }
}

/**
 * POST /api/v1/validation-results/{id}/resolve (api-spec O-25)
 *
 * 이 기록 보류 — 그 일지의 처리를 여기서 끝내고 큐에서 뺀다.
 * "수정한 원본 다시 올리기"(reupload)는 서버가 더 받지 않는다(#357). 본문은 reviseBlockedItem 으로 고친다.
 * 이미 처리된 건은 400 VALIDATION_RESULT_INVALID_REQUEST, 그 일지에 더 최근 판정이 생겼으면(이미 고쳐 다시 검사했는데
 * 또 막힘) 400 VALIDATION_RESULT_OUTDATED, 없는 건은 404 다.
 */
export async function resolveBlockedItem(id: string, action: "hold"): Promise<{ ok: true }> {
  if (mockFor("blockedQueue")) {
    const idx = mock.BLOCKED_QUEUE.findIndex((b) => b.id === id);
    if (idx !== -1) mock.BLOCKED_QUEUE.splice(idx, 1);
    return { ok: true };
  }
  return request(`/api/v1/validation-results/${id}/resolve`, {
    method: "POST",
    body: JSON.stringify({ action }),
  });
}

/**
 * 고친 본문 최대 길이. 서버 JournalEntry.MAX_CONTENT_LENGTH 와 같다 (#424) — 고친 본문은 그대로 검증 AI 에 가므로
 * 업로드한 일지 하나와 같은 길이까지만 받는다.
 */
export const MAX_REVISED_CONTENT = 5_000;

/**
 * POST /api/v1/validation-results/{id}/revise (api-spec O-25, #357)
 *
 * 막힌 일지의 본문을 고쳐 검증을 다시 받게 한다. 아이는 그대로다. 성공하면 큐에서 빠지고,
 * 통과하면 요약 대기, 또 막히면 큐에 최신 사유로 다시 뜬다.
 * 400 은 code 로 이유를 나눈다.
 * - INVALID_REQUEST: 빈 본문·5000자 초과
 * - VALIDATION_RESULT_CONTENT_UNCHANGED: 앞뒤 공백 말고 고친 게 없다
 * - VALIDATION_RESULT_CHILD_NOT_SHARED: 아이가 삭제됐거나 명단에서 빠졌거나 우리 기관 동의가 꺼졌다 — 보류만 할 수 있다
 * - VALIDATION_RESULT_OUTDATED: 그 일지에 더 최근 판정이 있다(이미 고쳐 다시 검사했는데 또 막힘)
 * - VALIDATION_RESULT_INVALID_REQUEST: 이미 처리됐거나 BLOCK 판정이 아니다
 * 없는 건은 404 다. 그 기관 AI 처리 줄이 꽉 찼으면 429 AGENT_QUEUE_FULL 이다(isAgentQueueFull).
 */
export async function reviseBlockedItem(id: string, content: string): Promise<{ ok: true }> {
  if (mockFor("blockedQueue")) {
    const idx = mock.BLOCKED_QUEUE.findIndex((b) => b.id === id);
    if (idx !== -1) mock.BLOCKED_QUEUE.splice(idx, 1);
    return { ok: true };
  }
  return request(`/api/v1/validation-results/${id}/revise`, {
    method: "POST",
    body: JSON.stringify({ content }),
  });
}

/**
 * POST /api/v1/institutions/me/children — 아이 등록 (api-spec O-11).
 *
 * 등록과 함께 서버가 첫 초대 코드를 발급해 invitation 으로 준다. 한 트랜잭션이라
 * 503 INVITE_CODE_ISSUE_FAILED 면 아이도 등록되지 않았다 — 그대로 다시 요청하면 된다.
 * 같은 기관 명부에 이름·생년월일이 같은 아이가 있으면 409 DUPLICATE_CHILD (관리번호 중복을 먼저 본다).
 */
export async function registerChild(input: {
  name: string;
  birthDate: string;
  /** 기관 관리번호. 필수다 */
  externalId: string;
}): Promise<{ child: RegisteredChild; invitation: IssuedInvitation }> {
  if (mockFor("childRegister")) {
    const externalId = input.externalId.trim();
    if (mock.CHILDREN.some((c) => c.externalId === externalId)) {
      throw new ApiError(409, "DUPLICATE_EXTERNAL_ID");
    }
    const trimmedName = input.name.trim();
    if (mock.CHILDREN.some((c) => c.name === trimmedName && c.birthDate === input.birthDate)) {
      throw new ApiError(409, "DUPLICATE_CHILD");
    }
    const child: Child = {
      id: `child_${Math.random().toString(36).slice(2, 8)}`,
      name: trimmedName,
      birthDate: input.birthDate,
      externalId,
      status: "pending_consent",
      institutions: [{ institution: mock.MY_INSTITUTION, consent: "not_granted" }],
    };
    mock.CHILDREN.push(child);
    const { id, name, birthDate, status } = child;
    return {
      child: { id, name, birthDate, externalId, status },
      invitation: issueMockInvitation(id),
    };
  }
  return request("/api/v1/institutions/me/children", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 초대 코드 유효 시간. 서버(Invitation)와 같은 10분 — mock 에서만 쓴다 */
const INVITE_TTL_MS = 10 * 60 * 1000;
/** 서버(InviteCodeGenerator)와 같은 글자. 헷갈리는 0 O 1 I L 은 뺐다 */
const INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

function issueMockInvitation(childId: string): IssuedInvitation {
  const code = Array.from(
    { length: 8 },
    () => INVITE_ALPHABET[Math.floor(Math.random() * INVITE_ALPHABET.length)],
  ).join("");
  const now = Date.now();
  const invitation: LiveInvitation = {
    id: `inv_${Math.random().toString(36).slice(2, 8)}`,
    code,
    issuedAt: new Date(now).toISOString(),
    expiresAt: new Date(now + INVITE_TTL_MS).toISOString(),
  };
  (mock.INVITATIONS[childId] ??= []).unshift(invitation);
  const { id, expiresAt } = invitation;
  return { id, code, expiresAt };
}

const childPath = (childId: string) =>
  `/api/v1/institutions/me/children/${encodeURIComponent(childId)}`;

/**
 * POST …/children/{childId}/invitations — 초대 코드 추가 발급 (O-15).
 * 이전 코드는 그대로 살아 있다. 엄마·아빠에게 따로 보낼 수 있게 여러 개를 허용한다.
 */
export async function issueInvitation(childId: string): Promise<IssuedInvitation> {
  if (mockFor("invitations")) return issueMockInvitation(childId);
  return request(`${childPath(childId)}/invitations`, { method: "POST" });
}

/** GET …/children/{childId}/invitations — 아직 쓸 수 있는 코드만, 최근 발급 순 (O-16) */
export async function getLiveInvitations(childId: string): Promise<LiveInvitation[]> {
  if (mockFor("invitations")) {
    const now = Date.now();
    return (mock.INVITATIONS[childId] ?? []).filter((i) => Date.parse(i.expiresAt) > now);
  }
  return request(`${childPath(childId)}/invitations`);
}

/** DELETE …/children/{childId}/invitations/{invitationId} — 코드 취소 (O-17) */
export async function cancelInvitation(childId: string, invitationId: string): Promise<void> {
  if (mockFor("invitations")) {
    const list = mock.INVITATIONS[childId] ?? [];
    const idx = list.findIndex((i) => i.id === invitationId);
    if (idx === -1) throw new ApiError(404, "INVITATION_NOT_FOUND");
    list.splice(idx, 1);
    return;
  }
  return request(`${childPath(childId)}/invitations/${encodeURIComponent(invitationId)}`, {
    method: "DELETE",
  });
}

/** GET …/children/{childId}/guardians — 다른 기관을 통해 연결된 보호자도 나온다 (O-18) */
export async function getChildGuardians(childId: string): Promise<ChildGuardian[]> {
  if (mockFor("childGuardians")) return mock.CHILD_GUARDIANS[childId] ?? [];
  return request(`${childPath(childId)}/guardians`);
}

/**
 * DELETE …/children/{childId}/guardians/{userId} — 기관이 보호자 연결을 끊는다 (O-19).
 * 연결은 기관 구분 없는 전역 연결이라 **모든 기관에서 끊긴다.** 우리 기관 코드로 연결된
 * 보호자(linkedByThisOrganization)만 끊을 수 있다.
 */
export async function unlinkGuardian(childId: string, guardianUserId: string): Promise<void> {
  if (mockFor("childGuardians")) {
    const list = mock.CHILD_GUARDIANS[childId] ?? [];
    const target = list.find((g) => g.userId === guardianUserId);
    if (!target) throw new ApiError(404, "GUARDIAN_NOT_FOUND");
    if (!target.linkedByThisOrganization) {
      throw new ApiError(403, "GUARDIAN_LINKED_BY_OTHER_ORGANIZATION");
    }
    list.splice(list.indexOf(target), 1);
    return;
  }
  return request(`${childPath(childId)}/guardians/${encodeURIComponent(guardianUserId)}`, {
    method: "DELETE",
  });
}

/** GET /api/v1/institutions/me/inbox — 수신함 목록 (O-50). 본문은 없다 */
export async function getInbox(): Promise<InboxItem[]> {
  if (mockFor("inbox")) return mock.INBOX.map(({ content: _content, ...item }) => ({ ...item }));
  return request("/api/v1/institutions/me/inbox");
}

/**
 * GET /api/v1/institutions/me/inbox/{insightId} — 수신함 단건 (O-51). 처음 열면 열람 시각이 남는다.
 *
 * 없는 글 · 다른 기관에 열린 글 · 동의가 꺼졌거나 명부에서 빠진 아이의 글은 모두
 * 404 SHARING_NOT_FOUND 다. 이유를 나누지 않는다.
 */
export async function getInboxItem(insightId: string): Promise<InboxDetail> {
  if (mockFor("inbox")) {
    const item = mock.INBOX.find((i) => i.id === insightId);
    if (!item) throw new ApiError(404, "SHARING_NOT_FOUND", "인사이트를 찾을 수 없습니다.");
    item.readAt ??= new Date().toISOString();
    return { ...item };
  }
  return request(`/api/v1/institutions/me/inbox/${encodeURIComponent(insightId)}`);
}

/* ── 학부모 ─────────────────────────────────────────── */

/**
 * POST /api/v1/guardians/me/invitations/accept — 초대 코드 수락 (api-spec G-04).
 *
 * 공백 · 하이픈 · 대소문자는 서버가 맞춘다. 화면에 보인 그대로 보내도 된다.
 * 실패는 ApiError 로 던진다 — 400 INVALID_INVITE_CODE · 409 LINK_ALREADY_EXISTS ·
 * 403 PARENT_REQUIRED 를 화면이 나눠 안내한다.
 */
export async function acceptInvitation(code: string): Promise<AcceptedInvitation> {
  if (mockFor("guardianInvite")) {
    const normalized = code.replace(/[\s-]/g, "").toUpperCase();
    const valid =
      normalized.length === 8 && [...normalized].every((c) => INVITE_ALPHABET.includes(c));
    if (!valid) throw new ApiError(400, "INVALID_INVITE_CODE");
    // mock 은 형식만 맞으면 데모 아이 하나에 연결한다
    const child = mock.CHILDREN.find((c) => c.id === mock.INVITED_CHILD_ID);
    if (!child) throw new ApiError(400, "INVALID_INVITE_CODE");
    if (mock.PARENT_CHILDREN.includes(child)) throw new ApiError(409, "LINK_ALREADY_EXISTS");
    mock.PARENT_CHILDREN.push(child);
    const institution = child.institutions[0].institution;
    return {
      childId: child.id,
      childName: child.name,
      organizationName: institution.name,
      organizationId: institution.id,
      childStatus: child.status,
    };
  }
  return changesParentChildren(
    request("/api/v1/guardians/me/invitations/accept", {
      method: "POST",
      body: JSON.stringify({ code }),
    }),
  );
}

/**
 * DELETE /api/v1/guardians/me/children/{childId} — 보호자 본인이 연결을 끊는다 (api-spec G-05).
 * 다시 연결하려면 기관에서 새 코드를 받아야 한다.
 */
export async function unlinkChild(childId: string): Promise<void> {
  if (mockFor("guardianUnlink")) {
    const idx = mock.PARENT_CHILDREN.findIndex((c) => c.id === childId);
    if (idx === -1) throw new ApiError(403, "FORBIDDEN");
    mock.PARENT_CHILDREN.splice(idx, 1);
    return;
  }
  return changesParentChildren(
    request(`/api/v1/guardians/me/children/${encodeURIComponent(childId)}`, {
      method: "DELETE",
    }),
  );
}

/**
 * GET /api/v1/children/{childId}/consent-preview?institutionId= (api-spec G-02)
 *
 * P-02 가 그릴 아이 · 기관 · 공유 항목 코드. 동의할 것이 없으면 null 이다 — 다른 아이나
 * 기관으로 대신 채우지 않는다. 동의 화면에 엉뚱한 아이가 뜨면 잘못 동의하게 된다.
 * 이미 동의한 기관도 "없는 요청" 으로 본다. 동의했다가 꺼진 기관은 다시 동의할 수 있다(서버와 같음).
 */
export async function getConsentPreview(
  childId: string,
  institutionId: string,
): Promise<ConsentPreview | null> {
  if (mockFor("guardianConsent")) {
    const child = mock.PARENT_CHILDREN.find((c) => c.id === childId);
    const rec = child?.institutions.find(
      (i) => i.institution.id === institutionId && i.consent !== "granted",
    );
    if (!child || !rec) return null;
    return {
      child: { id: child.id, name: child.name, birthDate: child.birthDate },
      institution: rec.institution,
      documentUrl: null,
      allowedFields: ALL_CONSENT_FIELDS,
    };
  }
  try {
    return await request<ConsentPreview>(
      `/api/v1/children/${encodeURIComponent(childId)}/consent-preview?institutionId=${encodeURIComponent(institutionId)}`,
    );
  } catch (e) {
    // 없는 요청은 화면에서 안내한다. 그 밖의 오류는 그대로 올려 에러 화면으로 보낸다.
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

/**
 * GET /api/v1/guardians/me/children — 이 보호자에게 연결된 아이 전체.
 * 입구와 화면 loader 가 함께 물으면 응답 하나를 같이 쓴다 (lib/parentChildrenCache)
 */
export async function getParentChildren(): Promise<Child[]> {
  return (await getParentChildrenWithOwner()).children;
}

/** 아이 목록과, 그 목록을 가져온 계정의 userId(응답 헤더 `X-Itda-User-Id`). 헤더가 없으면 null. */
export type ParentChildrenWithOwner = { children: Child[]; ownerUserId: string | null };

/**
 * 아이 목록을 그 목록을 가져온 계정과 함께 받는다. 설정 화면이 탈퇴 대상을 화면에 보이는 목록과 **같은 응답**에서 정한다 —
 * `/auth/me` 를 따로 물으면 다른 탭에서 계정이 바뀐 직후 목록은 A, 계정은 B 가 될 수 있다.
 */
export async function getParentChildrenWithOwner(): Promise<ParentChildrenWithOwner> {
  // 모의 목록에는 실제 소유 계정이 없다. 로그인도 모의일 때만 모의 계정("mock")을 싣는다 — 실제 로그인과 섞으면 탈퇴 대상이 실제
  // 계정과 맞지 않아 버튼이 늘 막히므로, 비워 두어 탈퇴 버튼을 숨긴다
  if (mockFor("guardianChildren")) {
    return { children: mock.PARENT_CHILDREN, ownerUserId: isAuthMock() ? "mock" : null };
  }
  return shareParentChildren(async () => {
    const { data, headers } = await requestWithHeaders<Child[]>("/api/v1/guardians/me/children");
    return { children: data, ownerUserId: headers.get("X-Itda-User-Id") };
  });
}

/**
 * 아이 목록에 담긴 값(아이 · 기관별 동의 · 연결 요청 · 돌봄 정보)을 바꾸는 요청. 끝나면 같이 쓰던 목록을 버린다 —
 * 바로 뒤의 다시 불러오기가 바뀌기 전 목록을 받지 않게. 실패해도 버린다(그 사이 서버 상태가 달라졌을 수 있다)
 */
async function changesParentChildren<T>(send: Promise<T>): Promise<T> {
  try {
    return await send;
  } finally {
    forgetParentChildren();
  }
}

/**
 * 보호자가 첫 기관 연결을 마쳤는지 본다. 이 브라우저에 표시가 없으면 서버에 묻는다.
 * 약관 동의는 여기서 보지 않는다 — 서버가 `/auth/me` 의 termsAgreed 로 따로 알려준다.
 *
 * 표시(`itda_parent_onboarded`)는 브라우저마다 따로라, 다른 브라우저 · 기기로 들어온 보호자는
 * 아이 연결과 동의를 마쳤어도 초대 코드 화면으로 돌아갔다. 연결된 아이가 한 명이라도 있으면
 * 첫 기관 동의까지 끝낸 것이라(동의해야 연결된다) 표시를 다시 남기고 마친 것으로 본다.
 *
 * - **약관에 동의한 뒤에만 부른다.** 앞에서 부르면 아이 목록이 403 TERMS_NOT_AGREED 다
 * - mock 아이 목록은 온보딩과 관계없이 늘 있어 근거가 못 된다. mock 이면 표시만 본다
 * - 서버가 실패하면 마치지 않은 것으로 본다 — 온보딩 화면을 한 번 더 볼 뿐, 로그인 흐름은 막지 않는다
 */
export async function restoreParentOnboarded(): Promise<boolean> {
  if (isOnboarded()) return true;
  if (mockFor("guardianChildren")) return false;
  try {
    const kids = await getParentChildren();
    if (kids.length === 0) return false;
    markOnboarded();
    return true;
  } catch {
    return false;
  }
}

/**
 * GET /api/v1/guardians/me/home?childId=
 * childId 는 필수다 — 어느 아이를 볼지는 화면이 아이 목록에서 고른다(readSelectedChildId).
 * activity 는 승인된 일지 줄과 받은 인사이트 줄을 승인 시각 최근 순으로 섞은 5줄이다 (서버가 섞고 자른다).
 */
export async function getParentHome(childId: string): Promise<{
  child: Child;
  activity: ParentActivity[];
}> {
  if (mockFor("guardianChildren")) {
    const child = mock.PARENT_CHILDREN.find((c) => c.id === childId) ?? mock.PARENT_CHILD;
    return { child, activity: mock.PARENT_ACTIVITY[child.id] ?? [] };
  }
  return request(`/api/v1/guardians/me/home?childId=${encodeURIComponent(childId)}`);
}

/**
 * GET /api/v1/children/{childId}/institution-requests/pending (api-spec G-40)
 * "안읽음" 배지·알림 목록이 공통으로 쓰는 기준: 아이가 명부에 올라 있는데 아직 아무도 동의하지 않은
 * (not_granted) 기관. home/report/timeline/settings 헤더의 빨간 점도 전부 이 함수 하나로
 * 통일해서, 조건이 여러 곳에 따로따로 있다가 어긋나는 일이 없게 한다.
 */
export async function getPendingInstitutionRequests(
  childId: string,
): Promise<{ institution: Institution }[]> {
  if (mockFor("consentRequests")) {
    const child = mock.PARENT_CHILDREN.find((c) => c.id === childId);
    if (!child) return [];
    return child.institutions.filter((i) => i.consent === "not_granted");
  }
  return request(`/api/v1/children/${encodeURIComponent(childId)}/institution-requests/pending`);
}

/**
 * PUT /api/v1/children/{childId}/consent-scopes/{institutionId} — P-02 의 동의 (api-spec G-41).
 *
 * allowedFields 는 미리보기(G-02)가 준 코드를 그대로 보낸다. 서버가 모르는 항목(예: 화면 문구)을
 * 보내면 400 으로 거절한다.
 * 실패는 ApiError 로 던진다 — 404 CONSENT_TARGET_NOT_FOUND 는 그 사이 아이가 삭제됐거나
 * 기관 명부에서 빠진 경우다.
 */
export async function grantConsent(
  childId: string,
  institutionId: string,
  allowedFields: ConsentField[],
): Promise<ConsentResult> {
  if (mockFor("guardianConsent")) {
    const child = mock.PARENT_CHILDREN.find((c) => c.id === childId);
    const rec = child?.institutions.find((i) => i.institution.id === institutionId);
    if (!child || !rec) throw new ApiError(404, "CONSENT_TARGET_NOT_FOUND");
    rec.consent = "granted";
    child.status = "active";
    return { childStatus: "active", consent: "granted", allowedFields };
  }
  return changesParentChildren(
    request(
      `/api/v1/children/${encodeURIComponent(childId)}/consent-scopes/${encodeURIComponent(institutionId)}`,
      {
        method: "PUT",
        body: JSON.stringify({ action: "grant", allowedFields }),
      },
    ),
  );
}

/** 서버가 기관 유형과 관계없이 내려주는 공유 항목 전부 (ConsentFieldCatalog) */
export const ALL_CONSENT_FIELDS: ConsentField[] = ["daily_summary", "weekly_insight"];

/**
 * PUT /api/v1/children/{childId}/consent-scopes/{institutionId} — 설정 · 알림 화면용 (G-41).
 * allowedFields 는 grant 에서 1개 이상 필수이고 revoke 에서는 무시된다.
 *
 * revoke 는 이 기관에 대한 **모든 보호자**의 동의를 끈다. 화면은 응답의 consent 로 상태를 고친다.
 */
export async function updateConsent(
  childId: string,
  institutionId: string,
  body: { action: "grant" | "revoke"; allowedFields?: ConsentField[] },
): Promise<ConsentResult> {
  if (mockFor("guardianLinks")) {
    const child = mock.CHILDREN.find((c) => c.id === childId);
    const rec = child?.institutions.find((i) => i.institution.id === institutionId);
    if (!child || !rec) throw new ApiError(404, "CONSENT_TARGET_NOT_FOUND");
    rec.consent = body.action === "grant" ? "granted" : "revoked";
    child.status = child.institutions.some((i) => i.consent === "granted") ? "active" : "suspended";
    return {
      childStatus: child.status,
      consent: rec.consent,
      allowedFields: rec.consent === "granted" ? (body.allowedFields ?? []) : [],
    };
  }
  return changesParentChildren(
    request(`/api/v1/children/${childId}/consent-scopes/${institutionId}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  );
}

/**
 * GET /api/v1/institutions/lookup — 기관 코드로 조회만 한다, 아직 연결하지 않는다 (G-43).
 * 맞는 기관이 없으면 서버는 404 INSTITUTION_CODE_NOT_FOUND 를 주고, 여기서 null 로 바꾼다.
 */
export async function lookupInstitutionByCode(code: string): Promise<Institution | null> {
  if (mockFor("institutionCode")) return code.trim() ? mock.ART_ACADEMY : null;
  try {
    return await request(`/api/v1/institutions/lookup?code=${encodeURIComponent(code)}`);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return null;
    throw err;
  }
}

/**
 * POST /api/v1/children/{childId}/institutions — 기관 코드로 연결을 **요청**한다 (G-44).
 *
 * 바로 연결되지 않는다. 기관이 관리번호를 넣어 수락하면 아이가 그 기관 명부에 오르고, 그때
 * "새 기관 권한 요청" 알림(G-40)으로 떠서 보호자가 공유에 동의한다. 같은 보호자가 다시
 * 요청하면 기다리는 요청을 그대로 돌려준다. 이미 명부에 있으면 409 INSTITUTION_ALREADY_LINKED,
 * 코드가 맞지 않으면 404 INSTITUTION_CODE_NOT_FOUND 를 던진다.
 */
export async function addInstitutionByCode(
  childId: string,
  code: string,
): Promise<{ institution: Institution; status: "pending"; requestedAt: string }> {
  if (mockFor("institutionCode")) {
    if (!code.trim()) throw new ApiError(404, "INSTITUTION_CODE_NOT_FOUND");
    const child = mock.CHILDREN.find((c) => c.id === childId);
    if (!child) throw new ApiError(403, "FORBIDDEN");
    if (child.institutions.some((i) => i.institution.id === mock.ART_ACADEMY.id)) {
      throw new ApiError(409, "INSTITUTION_ALREADY_LINKED");
    }
    child.linkRequests ??= [];
    let pending = child.linkRequests.find((r) => r.institution.id === mock.ART_ACADEMY.id);
    if (!pending) {
      pending = { institution: mock.ART_ACADEMY, requestedAt: new Date().toISOString() };
      child.linkRequests.push(pending);
    }
    return { ...pending, status: "pending" };
  }
  return changesParentChildren(
    request(`/api/v1/children/${encodeURIComponent(childId)}/institutions`, {
      method: "POST",
      body: JSON.stringify({ code }),
    }),
  );
}

/**
 * PUT /api/v1/children/{childId}/care-info — 돌봄 정보를 통째로 덮어쓴다. 서버가 앞뒤 공백을 떼고 같은 알레르기를
 * 합친 값을 돌려준다. 조회는 아이 목록·홈의 child.care 로 한다
 */
export async function updateChildCare(childId: string, care: ChildCareInfo): Promise<ChildCareInfo> {
  if (mockFor("careInfo")) {
    const child = mock.CHILDREN.find((c) => c.id === childId);
    if (child) child.care = care;
    return care;
  }
  return changesParentChildren(
    request<ChildCareInfo>(`/api/v1/children/${encodeURIComponent(childId)}/care-info`, {
      method: "PUT",
      body: JSON.stringify(care),
    }),
  );
}

/**
 * GET /api/v1/children/{childId}/journal — 타임라인/홈에 쓰는 일지 목록. 관찰 날짜 최근 순, 같은 날은 승인
 * 최근 순이다 (서버가 정렬한다)
 */
export async function getJournal(childId: string): Promise<JournalEntry[]> {
  if (mockFor("guardianJournal")) {
    // 같은 날 안에서는 mock 배열 순서가 승인 순서다 (sort 는 안정적이다)
    return mock.JOURNAL.filter((j) => j.childId === childId).sort((a, b) =>
      b.date.localeCompare(a.date),
    );
  }
  return request(`/api/v1/children/${childId}/journal`);
}

/** GET /api/v1/journal/{id} — 볼 수 없는 일지(JOURNAL_NOT_FOUND)는 undefined. mock 과 같은 약속이다 */
export async function getJournalEntry(id: string): Promise<JournalEntry | undefined> {
  if (mockFor("guardianJournal")) return mock.JOURNAL.find((j) => j.id === id);
  try {
    return await request<JournalEntry>(`/api/v1/journal/${encodeURIComponent(id)}`);
  } catch (err) {
    if (err instanceof ApiError && err.code === "JOURNAL_NOT_FOUND") return undefined;
    throw err;
  }
}

/** GET /api/v1/guardians/me/insights — 내 모든 아이의 받은 인사이트, 승인 시각 최근 순 (서버가 정렬한다) */
export async function getGuardianInsights(): Promise<GuardianInsight[]> {
  if (mockFor("guardianInsights")) return mock.GUARDIAN_INSIGHTS.map((insight) => ({ ...insight }));
  return request("/api/v1/guardians/me/insights");
}

/**
 * GET /api/v1/guardians/me/children/{childId}/institution-shares — 이 아이 주간 리포트가 어느 기관에 언제 열렸고
 * 지금도 보이는지, 기관별로 (G-45). 연결되지 않은 아이면 403 을 그대로 던진다
 */
export async function getInstitutionShares(childId: string): Promise<InstitutionShares[]> {
  if (mockFor("institutionShares")) return mock.INSTITUTION_SHARES[childId] ?? [];
  return request(`/api/v1/guardians/me/children/${encodeURIComponent(childId)}/institution-shares`);
}

/**
 * GET /api/v1/guardians/me/insights/{insightId} — 처음 열면 열람 시각이 남는다.
 * 볼 수 없는 글(없음 · 남의 아이 · 연결 해제로 거둬짐 → SHARING_NOT_FOUND)은 undefined. 일지 상세와 같은 약속이다
 */
export async function getGuardianInsight(id: string): Promise<GuardianInsight | undefined> {
  if (mockFor("guardianInsights")) {
    const insight = mock.GUARDIAN_INSIGHTS.find((i) => i.id === id);
    if (!insight) return undefined;
    insight.readAt ??= new Date().toISOString();
    return { ...insight };
  }
  try {
    return await request<GuardianInsight>(`/api/v1/guardians/me/insights/${encodeURIComponent(id)}`);
  } catch (err) {
    if (err instanceof ApiError && err.code === "SHARING_NOT_FOUND") return undefined;
    throw err;
  }
}

/**
 * GET /api/v1/children/{childId}/checklists — 확인 안 한 것 전부(최신순) 뒤에 확인한 것 최근 20개 (G-50).
 * needsCheckOnly 면 확인 안 한 것만 — 헤더 알림 점 · 설정 개수 · 알림 화면이 화면마다 부른다.
 * 기관이 내린 요청과 아이가 그 기관 명부에서 빠진 요청은 오지 않는다.
 */
export async function getChecklists(
  childId: string,
  { needsCheckOnly = false }: { needsCheckOnly?: boolean } = {},
): Promise<ChecklistItem[]> {
  if (mockFor("checklists")) {
    const all = mock.CHECKLISTS[childId] ?? [];
    const open = all.filter((c) => c.status === "needs_check");
    return needsCheckOnly ? open : [...open, ...all.filter((c) => c.status === "confirmed").slice(0, 20)];
  }
  const query = needsCheckOnly ? "?status=needs_check" : "";
  return request(`/api/v1/children/${encodeURIComponent(childId)}/checklists${query}`);
}

/**
 * 확인 안 한 준비물·확인사항 수 — 헤더 알림 점 · 설정 개수 · 알림 화면이 덧붙여 보는 값.
 *
 * 이 값 하나 때문에 화면 전체가 실패하면 안 된다. 기능이 꺼져 있거나 조회가 실패하면(404 · 5xx · 네트워크) null 을 주고,
 * 화면은 준비물 표시만 숨긴 채 기관 권한 요청 등 나머지를 그대로 보인다. 다음 로더 실행 때 다시 묻는다.
 * 401 과 403 TERMS_NOT_AGREED 는 삼키지 않는다 — 다시 로그인 · 약관 단계로 보내는 기존 흐름이 맡는다.
 * 준비물 화면(P-12)은 이 함수를 쓰지 않고 오류를 그대로 받는다.
 */
export async function loadOpenChecklistCount(childId: string): Promise<number | null> {
  if (!isReady("checklists")) return null;
  try {
    return (await getChecklists(childId, { needsCheckOnly: true })).length;
  } catch (err) {
    if (err instanceof ApiError && (err.status === 401 || err.code === "TERMS_NOT_AGREED")) throw err;
    return null;
  }
}

/**
 * POST /api/v1/children/{childId}/checklists/{checklistId}/confirm — 이 아이 몫으로 한 번 확인한다 (G-51).
 * 이미 다른 보호자가 확인했으면 그 확인(누가 · 언제)을 돌려준다. 내렸거나 명부에서 빠진 요청은 404 CHECKLIST_NOT_FOUND.
 */
export async function confirmChecklist(childId: string, checklistId: string): Promise<ChecklistItem> {
  if (mockFor("checklists")) {
    const item = (mock.CHECKLISTS[childId] ?? []).find((c) => c.id === checklistId);
    if (!item) throw new ApiError(404, "CHECKLIST_NOT_FOUND");
    if (item.status === "needs_check") {
      item.status = "confirmed";
      item.confirmedAt = new Date().toISOString();
    }
    return item;
  }
  return request(
    `/api/v1/children/${encodeURIComponent(childId)}/checklists/${encodeURIComponent(checklistId)}/confirm`,
    { method: "POST" },
  );
}

/* ── 기관: 준비물·확인사항 ──────────────────────────── */

/**
 * GET /api/v1/institutions/me/checklists — 내리지 않은 요청 최신순 50개씩 (O-71).
 * before 는 앞 쪽의 nextBefore. 기준 요청을 내렸어도 이어 볼 수 있다.
 */
export async function getSentChecklists(before?: string): Promise<SentChecklistPage> {
  if (mockFor("checklists")) {
    const live = mock.SENT_CHECKLISTS;
    const start = before ? live.findIndex((c) => c.id === before) + 1 : 0;
    return { checklists: live.slice(start, start + 50), nextBefore: live.length > start + 50 ? live[start + 49].id : null };
  }
  const query = before ? `?before=${encodeURIComponent(before)}` : "";
  return request(`/api/v1/institutions/me/checklists${query}`);
}

/**
 * POST /api/v1/institutions/me/checklists — 명부의 아이들에게 한 번에 보낸다 (O-70).
 * 명부에 없는 아이가 섞이면 400 INVALID_CHECKLIST_TARGET. 보낸 뒤에는 고칠 수 없고 내리기만 한다.
 */
export async function sendChecklist(input: { items: string[]; childIds: string[] }): Promise<SentChecklist> {
  if (mockFor("checklists")) {
    const roster = await getChildren();
    const sent: SentChecklist = {
      id: `cl_${Date.now()}`,
      items: input.items.map((i) => i.trim()),
      sentAt: new Date().toISOString(),
      total: input.childIds.length,
      confirmed: 0,
      targets: input.childIds.map((id) => ({
        child: { id, name: roster.find((c) => c.id === id)?.name ?? null },
        onRoster: true,
        confirmedAt: null,
      })),
    };
    mock.SENT_CHECKLISTS.unshift(sent);
    return sent;
  }
  return request("/api/v1/institutions/me/checklists", { method: "POST", body: JSON.stringify(input) });
}

/** DELETE /api/v1/institutions/me/checklists/{id} — 통째로 내린다. 이미 내렸어도 204 (O-72) */
export async function cancelChecklist(id: string): Promise<void> {
  if (mockFor("checklists")) {
    const at = mock.SENT_CHECKLISTS.findIndex((c) => c.id === id);
    if (at >= 0) mock.SENT_CHECKLISTS.splice(at, 1);
    return;
  }
  return request(`/api/v1/institutions/me/checklists/${encodeURIComponent(id)}`, { method: "DELETE" });
}
