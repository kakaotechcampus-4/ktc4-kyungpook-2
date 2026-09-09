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
  ChatTurn,
  Child,
  InboxItem,
  Insight,
  MatchingItem,
  ParentActivity,
  SummaryItem,
  TimelineEntry,
} from "@/lib/types";

const USE_MOCK = process.env.NEXT_PUBLIC_USE_MOCK !== "false";
const BASE = process.env.API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
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
  if (USE_MOCK) return { gate1_status: body.decision === "approve" ? "approved" : "rejected" };
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
  if (USE_MOCK) return { gate2_status: body.decision === "approve" ? "approved" : "held" };
  return request(`/api/insights/${insightId}/gate2`, {
    method: "POST",
    body: JSON.stringify(body),
  });
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

export async function getParentHome(): Promise<{
  child: Child;
  activity: ParentActivity[];
}> {
  if (USE_MOCK) return { child: mock.PARENT_CHILD, activity: mock.PARENT_ACTIVITY };
  return request("/api/guardians/me/home");
}

/** PUT /api/children/{childId}/consent-scopes/{institutionId} */
export async function updateConsent(
  childId: string,
  institutionId: string,
  body: { allowed_fields: string[]; action: "grant" | "revoke" },
) {
  if (USE_MOCK) return { ok: true };
  return request(`/api/children/${childId}/consent-scopes/${institutionId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}
