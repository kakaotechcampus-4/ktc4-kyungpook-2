/**
 * API 심(seam).
 *
 * 지금은 전부 mock 을 돌려준다. 백엔드 엔드포인트가 열리면 각 함수 본문만
 * `request()` 호출로 바꾼다 — 화면 코드는 건드리지 않는다.
 * (멘토 피드백: "인터페이스만 맞게 가짜 API 연결한 후 나중에 실제 API 연결하기")
 *
 * ── 경로 ───────────────────────────────────────────────
 * 모든 경로에 `/api/v1` 을 붙인다 — "새 외부 API의 기본 경로는 /api/v1이다"
 * (docs/api/api-conventions.md). 예외로 `GET /api/health` 만 v1 이 없다.
 *
 * ⚠️ **아래 목록에 없는 경로는 아직 백엔드에 구현돼 있지 않다.** 경로는 위 규약을
 *    따른 추정이므로, 백엔드가 만들 때 실제 경로를 확인하고 맞춰야 한다.
 *
 *    구현된 것 (2026-09-22 기준)
 *      POST /api/v1/raw-records       multipart: file → 201
 *      GET  /api/v1/raw-records/{id}
 *      GET  /api/v1/raw-records
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

import { clearSession, csrfHeader } from "@/lib/auth";
import * as mock from "@/lib/mock/data";
import { GATE1_INDEX, MATCHING_INDEX } from "@/lib/pipeline";
import type {
  ActivityLog,
  BlockedItem,
  CareReport,
  ChatTurn,
  Child,
  ChildCareInfo,
  FileProgress,
  InboxItem,
  Insight,
  Institution,
  InstitutionRequestItem,
  JournalEntry,
  MatchingItem,
  MatchResolution,
  ParentActivity,
  PendingLink,
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
    throw new ApiError(res.status, body.code ?? "UNKNOWN", body.message);
  }
  // 204 No Content 에는 래퍼가 없다(api-conventions.md). 백엔드에 아직 204 를 주는
  // 곳은 없지만 규약이라 미리 받아둔다.
  if (res.status === 204) return undefined as T;

  // 성공 응답은 { result, data, message } 로 감싸여 온다. 화면이 쓰는 건 data 뿐이라
  // 여기서 벗겨서 돌려준다 — 화면 코드는 래퍼를 모른다.
  // 데이터가 없는 정상 처리는 data 가 생략될 수 있어 undefined 가 된다.
  const body = await res.json();
  return body.data as T;
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

/** GET /api/v1/institutions/{id}/children */
export async function getChildren(): Promise<Child[]> {
  if (USE_MOCK) return mock.CHILDREN;
  return request("/api/v1/institutions/me/children");
}

export async function getChild(id: string): Promise<Child | undefined> {
  if (USE_MOCK) return mock.CHILDREN.find((c) => c.id === id);
  return request(`/api/v1/children/${id}`);
}

/** GET /api/v1/children/{childId}/context */
export async function getTimeline(childId: string): Promise<TimelineEntry[]> {
  if (USE_MOCK) return mock.TIMELINE[childId] ?? [];
  return request(`/api/v1/children/${childId}/context`);
}

/**
 * GET /api/v1/raw-records — **구현됨**
 *
 * institutionId 는 서버가 인증 정보에서 가져가므로 보내지 않는다.
 * 매칭 상태별 필터는 아직 백엔드에 없다 — 추가되면 쿼리 파라미터를 붙인다.
 */
export async function getMatchingQueue(): Promise<MatchingItem[]> {
  if (USE_MOCK) return mock.MATCHING_QUEUE;
  return request("/api/v1/raw-records");
}

/** GET /api/v1/validation-results?status=BLOCK */
export async function getBlockedQueue(): Promise<BlockedItem[]> {
  if (USE_MOCK) return mock.BLOCKED_QUEUE;
  return request("/api/v1/validation-results?status=BLOCK");
}

/** GET /api/v1/summaries?gate1_status=pending */
export async function getGate1Queue(): Promise<SummaryItem[]> {
  if (USE_MOCK) return mock.GATE1_QUEUE;
  return request("/api/v1/summaries?gate1_status=pending");
}

/** POST /api/v1/summaries/{summaryId}/gate1 */
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
  return request(`/api/v1/summaries/${summaryId}/gate1`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** GET /api/v1/insights */
export async function getInsights(): Promise<Insight[]> {
  if (USE_MOCK) return mock.INSIGHTS;
  return request("/api/v1/insights");
}

export async function getInsight(id: string): Promise<Insight | undefined> {
  if (USE_MOCK) return mock.INSIGHTS.find((i) => i.id === id);
  return request(`/api/v1/insights/${id}`);
}

/**
 * POST /api/v1/insights/{insightId}/gate2
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
  return request(`/api/v1/insights/${insightId}/gate2`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/**
 * 확인 필요 큐에서 아이를 확정(또는 "우리 기관 아동 아님"으로 제외)하면 큐에서 빠진다.
 *
 * ⚠️ **이 엔드포인트는 백엔드에 없다.** RawRecordController 에는 업로드·조회만 있다.
 *    본문 형태({ action, childId })는 FE 가 제안하는 계약이다. 백엔드와 합의가 필요하다.
 */
export async function resolveMatchingItem(
  id: string,
  resolution: MatchResolution,
): Promise<{ ok: true }> {
  if (USE_MOCK) {
    const idx = mock.MATCHING_QUEUE.findIndex((m) => m.id === id);
    if (idx !== -1) mock.MATCHING_QUEUE.splice(idx, 1);
    return { ok: true };
  }
  return request(`/api/v1/raw-records/${id}/match`, {
    method: "POST",
    body: JSON.stringify(resolution),
  });
}

/**
 * Gate 1 에서 아이를 바꾼다. Gate 1 은 되돌릴 수 있는 단계라 여기서 고칠 수 있어야 한다.
 *
 * ⚠️ **이 엔드포인트는 백엔드에 없다.** 경로·본문은 FE 제안이다.
 *    아이가 바뀌면 검증(다른 아이 이름 포함 여부 등)을 다시 돌려야 할 수 있다 — 서버 쪽 판단이다.
 */
export async function reassignSummaryChild(
  summaryId: string,
  childId: string,
): Promise<{ ok: true }> {
  if (USE_MOCK) {
    const item = mock.GATE1_QUEUE.find((s) => s.id === summaryId);
    const child = mock.CHILDREN.find((c) => c.id === childId);
    if (item && child) {
      item.childId = child.id;
      item.childName = child.name;
      item.matchBasis = { source: "teacher", name: child.name };
    }
    return { ok: true };
  }
  return request(`/api/v1/summaries/${summaryId}/child`, {
    method: "PUT",
    body: JSON.stringify({ child_id: childId }),
  });
}

/* ── 업로드 · 처리 현황 ─────────────────────────────── */

/**
 * POST /api/v1/raw-records — **구현됨** (multipart: file → 201)
 *
 * 즉시 응답하고 매칭 · 검증 · 요약은 백그라운드에서 돈다. 파일마다 한 번씩 부른다.
 * 응답은 RawRecordResponse(backend/.../dto/RawRecordResponse.java) 다.
 */
export async function uploadRawRecords(files: File[]): Promise<FileProgress[]> {
  if (USE_MOCK) {
    const now = new Date().toISOString();
    const created = files.map((f, i) => ({
      rawRecordId: `rr_mock_${Date.now()}_${i}`,
      fileName: f.name,
      uploadedAt: now,
      entries: [],
    }));
    for (const file of created) simulated.set(file.rawRecordId, Date.now());
    mock.FILE_PROGRESS.unshift(...created);
    return created;
  }
  return Promise.all(
    files.map(async (file) => {
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
    }),
  );
}

/**
 * 파일별 처리 현황. 최근 업로드가 먼저 온다.
 *
 * ⚠️ **이 엔드포인트는 백엔드에 없다.** RawRecordResponse 에는 파일 상태(status) 하나뿐이라
 *    파일에서 나온 기록이 건별로 어느 단계에 있는지 알 수 없다. FileProgress 형태로 달라고
 *    요청해야 한다.
 */
export async function getFileProgress(): Promise<FileProgress[]> {
  if (USE_MOCK) {
    tickSimulation();
    return mock.FILE_PROGRESS;
  }
  return request("/api/v1/raw-records/progress");
}

/**
 * 실패한 기록을 다시 처리한다. 실패는 사람이 고를 게 아니라 시스템 오류라 재시도로 충분하다.
 * ⚠️ **이 엔드포인트는 백엔드에 없다.**
 */
export async function retryFailedEntries(rawRecordId: string): Promise<{ ok: true }> {
  if (USE_MOCK) {
    const file = mock.FILE_PROGRESS.find((f) => f.rawRecordId === rawRecordId);
    for (const e of file?.entries ?? []) {
      if (e.state === "failed") e.state = "running";
    }
    simulated.set(rawRecordId, Date.now());
    return { ok: true };
  }
  return request(`/api/v1/raw-records/${rawRecordId}/retry`, { method: "POST" });
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
  return request(`/api/v1/validation-results/${id}/resolve`, {
    method: "POST",
    body: JSON.stringify({ action }),
  });
}

/** POST /api/v1/institutions/me/children — 등록 + 초대코드 발급 */
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
  return request("/api/v1/institutions/me/children", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function generateInviteCode(): string {
  const s = () => Math.random().toString(36).slice(2, 6).toUpperCase();
  return `ITDA-${s()}-${s()}`;
}

/** GET /api/v1/institutions/me/inbox */
export async function getInbox(): Promise<InboxItem[]> {
  if (USE_MOCK) return mock.INBOX;
  return request("/api/v1/institutions/me/inbox");
}

/** POST /api/v1/children/{childId}/chat */
export async function askChat(childId: string, question: string): Promise<ChatTurn> {
  if (USE_MOCK) {
    const hit = mock.CHAT_EXAMPLES.find((t) => t.question === question);
    return hit ?? { question, answer: null, sources: [] };
  }
  return request(`/api/v1/children/${childId}/chat`, {
    method: "POST",
    body: JSON.stringify({ question }),
  });
}

/** GET /api/v1/audit-logs */
export async function getActivity(): Promise<ActivityLog[]> {
  if (USE_MOCK) return mock.ACTIVITY;
  return request("/api/v1/audit-logs");
}

/* ── 학부모 ─────────────────────────────────────────── */

/**
 * GET /api/v1/guardians/me/pending-links (api-spec G-01)
 *
 * 기관이 아이를 등록하면 서버가 만들어 두는 "연결 대기" 목록이다. 초대코드를 대신하는
 * 최초 연결 경로라, 보호자 온보딩의 마지막 단계가 이 목록을 그린다. 나중에 아이를 하나
 * 더 추가할 때도 같은 목록을 본다.
 *
 * 거절한 요청은 빼고 준다 — 알림 배지(getPendingInstitutionRequests)와 기준을 맞춘다.
 */
export async function getPendingLinks(): Promise<PendingLink[]> {
  if (USE_MOCK) {
    return mock.PARENT_CHILDREN.flatMap((child) =>
      child.institutions
        .filter(
          (i) =>
            i.consent === "not_granted" &&
            !mock.DECLINED_INSTITUTION_REQUESTS.has(`${child.id}:${i.institution.id}`),
        )
        .map((i) => ({
          child: { id: child.id, name: child.name, birthDate: child.birthDate },
          institution: i.institution,
          requestedAt: mock.PENDING_LINK_REQUESTED_AT[`${child.id}:${i.institution.id}`] ?? "",
        })),
    );
  }
  return request("/api/v1/guardians/me/pending-links");
}


/** GET /api/v1/guardians/me/children — 이 보호자에게 연결된 아이 전체 */
export async function getParentChildren(): Promise<Child[]> {
  if (USE_MOCK) return mock.PARENT_CHILDREN;
  return request("/api/v1/guardians/me/children");
}

/**
 * GET /api/v1/guardians/me/home?childId=
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
  return request(`/api/v1/guardians/me/home${childId ? `?childId=${childId}` : ""}`);
}

/**
 * GET /api/v1/children/{childId}/institution-requests/pending
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
  return request(`/api/v1/children/${childId}/institution-requests/pending`);
}

/** 알림에서 "거절" — consent 는 그대로 두고(다시 요청할 수 있으니) 배지에서만 뺀다 */
export async function declineInstitutionRequest(childId: string, institutionId: string) {
  if (USE_MOCK) {
    mock.DECLINED_INSTITUTION_REQUESTS.add(`${childId}:${institutionId}`);
    return { ok: true };
  }
  return request(`/api/v1/children/${childId}/institution-requests/${institutionId}/decline`, {
    method: "POST",
  });
}

/** PUT /api/v1/children/{childId}/consent-scopes/{institutionId} */
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
  return request(`/api/v1/children/${childId}/consent-scopes/${institutionId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

/** 기관 코드로 조회만 한다 — 아직 연결하지 않는다. */
export async function lookupInstitutionByCode(code: string): Promise<Institution | null> {
  if (USE_MOCK) return code.trim() ? mock.ART_ACADEMY : null;
  return request(`/api/v1/institutions/lookup?code=${encodeURIComponent(code)}`);
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
  return request(`/api/v1/children/${childId}/institutions`, {
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
  return request(`/api/v1/children/${childId}/care-info`, {
    method: "PUT",
    body: JSON.stringify(care),
  });
}

/** GET /api/v1/children/{childId}/journal — 타임라인/홈에 쓰는 일지 목록 (최신순) */
export async function getJournal(childId: string): Promise<JournalEntry[]> {
  if (USE_MOCK) {
    return mock.JOURNAL.filter((j) => j.childId === childId).sort((a, b) =>
      `${b.date}${b.time}`.localeCompare(`${a.date}${a.time}`),
    );
  }
  return request(`/api/v1/children/${childId}/journal`);
}

export async function getJournalEntry(id: string): Promise<JournalEntry | undefined> {
  if (USE_MOCK) return mock.JOURNAL.find((j) => j.id === id);
  return request(`/api/v1/journal/${id}`);
}

/** POST /api/v1/journal/{id}/flag — "이 내용이 이상해요": 기관에 재확인 요청 */
export async function flagJournalEntry(id: string) {
  if (USE_MOCK) {
    const entry = mock.JOURNAL.find((j) => j.id === id);
    if (entry) entry.flagged = true;
    return { ok: true };
  }
  return request(`/api/v1/journal/${id}/flag`, { method: "POST" });
}

/** GET /api/v1/children/{childId}/care-report?period= — 아직 리포트가 없으면 null */
export async function getCareReport(
  childId: string,
  period: "weekly" | "monthly",
): Promise<CareReport | null> {
  if (USE_MOCK) return mock.CARE_REPORTS[childId]?.[period] ?? null;
  return request(`/api/v1/children/${childId}/care-report?period=${period}`);
}

/** GET /api/v1/children/{childId}/institution-requests */
export async function getInstitutionRequests(childId: string): Promise<InstitutionRequestItem[]> {
  if (USE_MOCK) return mock.INSTITUTION_REQUESTS[childId] ?? [];
  return request(`/api/v1/children/${childId}/institution-requests`);
}

/** 타임라인/홈 상단 TODAY 카드 — 아이별. 없으면 null (화면에서 숨김) */
export async function getTodaySummary(childId: string): Promise<string | null> {
  if (USE_MOCK) return mock.PARENT_TODAY_SUMMARY[childId] ?? null;
  return request(`/api/v1/children/${childId}/today-summary`);
}

/** POST /api/v1/institution-requests/{id}/confirm */
export async function confirmInstitutionRequest(id: string) {
  if (USE_MOCK) {
    const req = Object.values(mock.INSTITUTION_REQUESTS)
      .flat()
      .find((r) => r.id === id);
    if (req) req.status = "confirmed";
    return { ok: true };
  }
  return request(`/api/v1/institution-requests/${id}/confirm`, { method: "POST" });
}
