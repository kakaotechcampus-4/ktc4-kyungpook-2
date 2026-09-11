/**
 * API 심(seam).
 *
 * 지금은 전부 mock 을 돌려준다. 백엔드 엔드포인트가 열리면 각 함수 본문만
 * `request()` 호출로 바꾼다 — 화면 코드는 건드리지 않는다.
 * (멘토 피드백: "인터페이스만 맞게 가짜 API 연결한 후 나중에 실제 API 연결하기")
 *
 * 주석의 경로는 기획서 11절 API Interface Specification 을 따른다.
 *
 * ⚠️ 이미 구현된 엔드포인트가 develop 에 아직 머지되지 않은 브랜치에 있다.
 *    실제 계약이 기획서와 다르므로 연결할 때 아래를 기준으로 한다.
 *
 *    feat/be/#4 — 원본 기록 (S3 저장)
 *      POST /api/raw-records            multipart: institutionId, file → 201
 *      GET  /api/raw-records/{id}
 *      GET  /api/raw-records?institutionId=
 *      응답: { id, institutionId, originalFilename, contentType, sizeBytes,
 *              status, createdAt }
 *      → 기획서 11.1 의 { type, local_path, captured_at } JSON 과 다르다.
 *
 *    feat/be/#3 — 인증 (카카오 OAuth + JWT)
 *      POST /api/auth/kakao?code=...    → { accessToken, kakaoId, nickname }
 *      /api/auth/** 는 permitAll, 나머지는 JWT 필요
 *      → 기획서 12절의 SMS OTP 와 다르다. 로그인 화면 재작업이 필요하다.
 */

import * as mock from "@/lib/mock/data";
import type {
  ActivityLog,
  BlockedItem,
  CareReport,
  ChatTurn,
  Child,
  ChildCareInfo,
  InboxItem,
  Insight,
  Institution,
  InstitutionRequestItem,
  JournalEntry,
  MatchingItem,
  ParentActivity,
  SummaryItem,
  TimelineEntry,
} from "@/lib/types";

/**
 * mock 모드에서 GET 함수들은 `mock.*` 배열을 그대로 참조로 돌려준다(복사하지 않음).
 * 그래서 아래 mutate 계열 함수들이 그 배열을 in-place 로 고치면, 다음 GET 호출이나
 * 다른 라우트(사이드바 배지 등)가 즉시 바뀐 상태를 보게 된다 — 별도 서버 없이도
 * "승인 이력이 유지된다"를 흉내낼 수 있는 이유.
 */

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "false";
const BASE = import.meta.env.VITE_API_BASE_URL ?? "";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: { "content-type": "application/json", ...(init?.headers ?? {}) },
    cache: "no-store",
  });
  if (!res.ok) {
    // 에러 코드는 기획서 12절 인증/권한 공통 Error 형식을 따른다.
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body.error_code ?? "UNKNOWN", body.message);
  }
  return res.json() as Promise<T>;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message?: string,
  ) {
    super(message ?? code);
  }
}

/* ── 기관 ───────────────────────────────────────────── */

/** GET /api/institutions/{id}/children */
export async function getChildren(): Promise<Child[]> {
  if (USE_MOCK) return mock.CHILDREN;
  return request("/api/institutions/me/children");
}

export async function getChild(id: string): Promise<Child | undefined> {
  if (USE_MOCK) return mock.CHILDREN.find((c) => c.id === id);
  return request(`/api/children/${id}`);
}

/** GET /api/children/{childId}/context */
export async function getTimeline(childId: string): Promise<TimelineEntry[]> {
  if (USE_MOCK) return mock.TIMELINE[childId] ?? [];
  return request(`/api/children/${childId}/context`);
}

/**
 * GET /api/raw-records?institutionId=  (feat/be/#4 구현 기준)
 * 매칭 상태별 필터는 아직 백엔드에 없다 — 추가되면 쿼리 파라미터를 붙인다.
 */
export async function getMatchingQueue(): Promise<MatchingItem[]> {
  if (USE_MOCK) return mock.MATCHING_QUEUE;
  return request("/api/raw-records?institutionId=me");
}

/** GET /api/validation-results?status=BLOCK */
export async function getBlockedQueue(): Promise<BlockedItem[]> {
  if (USE_MOCK) return mock.BLOCKED_QUEUE;
  return request("/api/validation-results?status=BLOCK");
}

/** GET /api/summaries?gate1_status=pending */
export async function getGate1Queue(): Promise<SummaryItem[]> {
  if (USE_MOCK) return mock.GATE1_QUEUE;
  return request("/api/summaries?gate1_status=pending");
}

/** POST /api/summaries/{summaryId}/gate1 */
export async function decideGate1(
  summaryId: string,
  body: { decision: "approve" | "reject"; edited_content?: string; reason?: string },
) {
  if (USE_MOCK) {
    const item = mock.GATE1_QUEUE.find((s) => s.id === summaryId);
    if (item) {
      item.gate1Status = body.decision === "approve" ? "approved" : "rejected";
      if (body.edited_content) item.content = body.edited_content;
      if (body.reason) item.rejectReason = body.reason;
    }
    return { gate1_status: item?.gate1Status ?? "pending" };
  }
  return request(`/api/summaries/${summaryId}/gate1`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** GET /api/insights */
export async function getInsights(): Promise<Insight[]> {
  if (USE_MOCK) return mock.INSIGHTS;
  return request("/api/insights");
}

export async function getInsight(id: string): Promise<Insight | undefined> {
  if (USE_MOCK) return mock.INSIGHTS.find((i) => i.id === id);
  return request(`/api/insights/${id}`);
}

/**
 * POST /api/insights/{insightId}/gate2
 * 수신 기관을 개별로 골라 보낸다. 기본값은 전체 미선택이며,
 * 빈 배열이면 호출 자체를 하지 않는다.
 */
export async function decideGate2(
  insightId: string,
  body: { decision: "approve" | "hold"; target_institution_ids: string[] },
) {
  if (USE_MOCK) {
    const item = mock.INSIGHTS.find((i) => i.id === insightId);
    if (item) item.gate2Status = body.decision === "approve" ? "approved" : "held";
    return { gate2_status: item?.gate2Status ?? "pending" };
  }
  return request(`/api/insights/${insightId}/gate2`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** 확인 필요 큐에서 아이를 확정(또는 "우리 기관 아동 아님"으로 제외)하면 큐에서 빠진다. */
export async function resolveMatchingItem(id: string): Promise<{ ok: true }> {
  if (USE_MOCK) {
    const idx = mock.MATCHING_QUEUE.findIndex((m) => m.id === id);
    if (idx !== -1) mock.MATCHING_QUEUE.splice(idx, 1);
    return { ok: true };
  }
  return request(`/api/raw-records/${id}/match`, { method: "POST" });
}

/** 재입력 요청 큐 항목을 처리(재업로드 또는 보류)하면 큐에서 빠진다. */
export async function resolveBlockedItem(
  id: string,
  action: "reupload" | "hold",
): Promise<{ ok: true }> {
  if (USE_MOCK) {
    const idx = mock.BLOCKED_QUEUE.findIndex((b) => b.id === id);
    if (idx !== -1) mock.BLOCKED_QUEUE.splice(idx, 1);
    return { ok: true };
  }
  return request(`/api/validation-results/${id}/resolve`, {
    method: "POST",
    body: JSON.stringify({ action }),
  });
}

/** POST /api/institutions/me/children — 등록 + 초대코드 발급 */
export async function registerChild(input: {
  name: string;
  birthDate: string;
}): Promise<{ child: Child; inviteCode: string }> {
  const inviteCode = generateInviteCode();
  if (USE_MOCK) {
    const child: Child = {
      id: `child_${Math.random().toString(36).slice(2, 8)}`,
      name: input.name,
      birthDate: input.birthDate,
      status: "pending_consent",
      institutions: [{ institution: mock.MY_INSTITUTION, consent: "not_granted" }],
    };
    mock.CHILDREN.push(child);
    return { child, inviteCode };
  }
  return request("/api/institutions/me/children", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function generateInviteCode(): string {
  const s = () => Math.random().toString(36).slice(2, 6).toUpperCase();
  return `ITDA-${s()}-${s()}`;
}

/** GET /api/institutions/me/inbox */
export async function getInbox(): Promise<InboxItem[]> {
  if (USE_MOCK) return mock.INBOX;
  return request("/api/institutions/me/inbox");
}

/** POST /api/children/{childId}/chat */
export async function askChat(childId: string, question: string): Promise<ChatTurn> {
  if (USE_MOCK) {
    const hit = mock.CHAT_EXAMPLES.find((t) => t.question === question);
    return hit ?? { question, answer: null, sources: [] };
  }
  return request(`/api/children/${childId}/chat`, {
    method: "POST",
    body: JSON.stringify({ question }),
  });
}

/** GET /api/audit-logs */
export async function getActivity(): Promise<ActivityLog[]> {
  if (USE_MOCK) return mock.ACTIVITY;
  return request("/api/audit-logs");
}

/* ── 학부모 ─────────────────────────────────────────── */

/** GET /api/guardians/me/children — 이 보호자에게 연결된 아이 전체 */
export async function getParentChildren(): Promise<Child[]> {
  if (USE_MOCK) return mock.PARENT_CHILDREN;
  return request("/api/guardians/me/children");
}

/**
 * GET /api/guardians/me/home?childId=
 * childId 를 생략하면 첫 번째 아이(mock: PARENT_CHILDREN[0]) 기준.
 */
export async function getParentHome(childId?: string): Promise<{
  child: Child;
  activity: ParentActivity[];
}> {
  if (USE_MOCK) {
    const child = mock.PARENT_CHILDREN.find((c) => c.id === childId) ?? mock.PARENT_CHILD;
    return { child, activity: mock.PARENT_ACTIVITY[child.id] ?? [] };
  }
  return request(`/api/guardians/me/home${childId ? `?childId=${childId}` : ""}`);
}

/**
 * GET /api/children/{childId}/institution-requests/pending
 * "안읽음" 배지·알림 목록이 공통으로 쓰는 기준: not_granted 이면서 거절되지
 * 않은 기관 요청. home/report/timeline 헤더의 빨간 점도 전부 이 함수 하나로
 * 통일해서, 조건이 여러 곳에 따로따로 있다가 어긋나는 일이 없게 한다.
 */
export async function getPendingInstitutionRequests(
  childId: string,
): Promise<{ institution: Institution }[]> {
  if (USE_MOCK) {
    const child = mock.PARENT_CHILDREN.find((c) => c.id === childId);
    if (!child) return [];
    return child.institutions.filter(
      (i) =>
        i.consent === "not_granted" &&
        !mock.DECLINED_INSTITUTION_REQUESTS.has(`${childId}:${i.institution.id}`),
    );
  }
  return request(`/api/children/${childId}/institution-requests/pending`);
}

/** 알림에서 "거절" — consent 는 그대로 두고(다시 요청할 수 있으니) 배지에서만 뺀다 */
export async function declineInstitutionRequest(childId: string, institutionId: string) {
  if (USE_MOCK) {
    mock.DECLINED_INSTITUTION_REQUESTS.add(`${childId}:${institutionId}`);
    return { ok: true };
  }
  return request(`/api/children/${childId}/institution-requests/${institutionId}/decline`, {
    method: "POST",
  });
}

/** PUT /api/children/{childId}/consent-scopes/{institutionId} */
export async function updateConsent(
  childId: string,
  institutionId: string,
  body: { allowed_fields: string[]; action: "grant" | "revoke" },
) {
  if (USE_MOCK) {
    const child = mock.CHILDREN.find((c) => c.id === childId);
    const rec = child?.institutions.find((i) => i.institution.id === institutionId);
    if (rec) rec.consent = body.action === "grant" ? "granted" : "revoked";
    return { ok: true };
  }
  return request(`/api/children/${childId}/consent-scopes/${institutionId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

/** 기관 코드로 조회만 한다 — 아직 연결하지 않는다. */
export async function lookupInstitutionByCode(code: string): Promise<Institution | null> {
  if (USE_MOCK) return code.trim() ? mock.ART_ACADEMY : null;
  return request(`/api/institutions/lookup?code=${encodeURIComponent(code)}`);
}

/**
 * 초대코드 없이 보호자가 기관 코드를 직접 입력해서 연결한다.
 * mock 에서는 코드 형식과 무관하게 데모 기관(미술학원)을 찾아준다.
 */
export async function addInstitutionByCode(
  childId: string,
  code: string,
): Promise<{ institution: Institution } | null> {
  if (USE_MOCK) {
    if (!code.trim()) return null;
    const child = mock.CHILDREN.find((c) => c.id === childId);
    if (!child) return null;
    const exists = child.institutions.some((i) => i.institution.id === mock.ART_ACADEMY.id);
    if (!exists) child.institutions.push({ institution: mock.ART_ACADEMY, consent: "granted" });
    return { institution: mock.ART_ACADEMY };
  }
  return request(`/api/children/${childId}/institutions`, {
    method: "POST",
    body: JSON.stringify({ code }),
  });
}

/** 보호자가 온보딩·동의 관리 화면에서 입력하는 돌봄 정보 저장 */
export async function updateChildCare(childId: string, care: ChildCareInfo) {
  if (USE_MOCK) {
    const child = mock.CHILDREN.find((c) => c.id === childId);
    if (child) child.care = care;
    return { ok: true };
  }
  return request(`/api/children/${childId}/care-info`, {
    method: "PUT",
    body: JSON.stringify(care),
  });
}

/** GET /api/children/{childId}/journal — 타임라인/홈에 쓰는 일지 목록 (최신순) */
export async function getJournal(childId: string): Promise<JournalEntry[]> {
  if (USE_MOCK) {
    return mock.JOURNAL.filter((j) => j.childId === childId).sort((a, b) =>
      `${b.date}${b.time}`.localeCompare(`${a.date}${a.time}`),
    );
  }
  return request(`/api/children/${childId}/journal`);
}

export async function getJournalEntry(id: string): Promise<JournalEntry | undefined> {
  if (USE_MOCK) return mock.JOURNAL.find((j) => j.id === id);
  return request(`/api/journal/${id}`);
}

/** POST /api/journal/{id}/flag — "이 내용이 이상해요": 기관에 재확인 요청 */
export async function flagJournalEntry(id: string) {
  if (USE_MOCK) {
    const entry = mock.JOURNAL.find((j) => j.id === id);
    if (entry) entry.flagged = true;
    return { ok: true };
  }
  return request(`/api/journal/${id}/flag`, { method: "POST" });
}

/** GET /api/children/{childId}/care-report?period= — 아직 리포트가 없으면 null */
export async function getCareReport(
  childId: string,
  period: "weekly" | "monthly",
): Promise<CareReport | null> {
  if (USE_MOCK) return mock.CARE_REPORTS[childId]?.[period] ?? null;
  return request(`/api/children/${childId}/care-report?period=${period}`);
}

/** GET /api/children/{childId}/institution-requests */
export async function getInstitutionRequests(childId: string): Promise<InstitutionRequestItem[]> {
  if (USE_MOCK) return mock.INSTITUTION_REQUESTS[childId] ?? [];
  return request(`/api/children/${childId}/institution-requests`);
}

/** 타임라인/홈 상단 TODAY 카드 — 아이별. 없으면 null (화면에서 숨김) */
export async function getTodaySummary(childId: string): Promise<string | null> {
  if (USE_MOCK) return mock.PARENT_TODAY_SUMMARY[childId] ?? null;
  return request(`/api/children/${childId}/today-summary`);
}

/** POST /api/institution-requests/{id}/confirm */
export async function confirmInstitutionRequest(id: string) {
  if (USE_MOCK) {
    const req = Object.values(mock.INSTITUTION_REQUESTS)
      .flat()
      .find((r) => r.id === id);
    if (req) req.status = "confirmed";
    return { ok: true };
  }
  return request(`/api/institution-requests/${id}/confirm`, { method: "POST" });
}
