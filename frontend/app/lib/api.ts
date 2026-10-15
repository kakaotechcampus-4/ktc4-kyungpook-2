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
 *    구현된 것 (2026-10-12 기준, BE_READY 에서 true 인 기능)
 *      POST /api/v1/raw-records       multipart: file → 201
 *      GET  /api/v1/raw-records/{id}
 *      GET  /api/v1/raw-records
 *      GET  /api/v1/institutions/me/children
 *      POST /api/v1/institutions/me/children   → 201 (초대 코드 포함)
 *      POST   /api/v1/institutions/me/children/{childId}/invitations        → 201
 *      GET    /api/v1/institutions/me/children/{childId}/invitations
 *      DELETE /api/v1/institutions/me/children/{childId}/invitations/{id}   → 204
 *      GET    /api/v1/institutions/me/children/{childId}/guardians
 *      DELETE /api/v1/institutions/me/children/{childId}/guardians/{userId} → 204
 *      POST   /api/v1/guardians/me/invitations/accept
 *      DELETE /api/v1/guardians/me/children/{childId}   → 204 (아래 guardianUnlink 참고)
 *      GET  /api/v1/matching-queue
 *      POST /api/v1/matching-queue/{id}/resolve
 *      GET  /api/v1/auth/me           (lib/auth.ts 에서 호출)
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
import { clearSession, csrfHeader } from "@/lib/auth";
import * as mock from "@/lib/mock/data";
import { GATE1_INDEX, MATCHING_INDEX } from "@/lib/pipeline";
import type {
  AcceptedInvitation,
  ActivityLog,
  BlockedItem,
  CareReport,
  ChatTurn,
  Child,
  ChildCareInfo,
  ChildGuardian,
  ChildListItem,
  ConsentField,
  ConsentPreview,
  ConsentResult,
  FileProgress,
  InboxItem,
  Insight,
  Institution,
  InstitutionRequestItem,
  IssuedInvitation,
  JournalEntry,
  LiveInvitation,
  MatchingItem,
  MatchResolution,
  ParentActivity,
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
  /** 아이 상세 · 타임라인 */
  childProfile: false,
  /** 아이 기록 기반 질의응답 */
  chat: false,
  /** 확인 필요 큐 조회 · 처리 (O-22, O-23) */
  matchingQueue: true,
  /** 수정 요청(BLOCK) 큐 조회 · 처리 */
  blockedQueue: false,
  /** 1차 검토 조회 · 승인 · 아이 변경 */
  gate1: false,
  /** 인사이트 조회 · 2차 공유 */
  insights: false,
  /** 원본 업로드 (POST /raw-records) */
  upload: true,
  /** 파일별 처리 현황 · 재시도 (O-26, O-27) */
  fileProgress: false,
  /** 기관 받은편지함 */
  inbox: false,
  /** 활동 기록 */
  activity: false,
  /** 보호자: 초대 코드 수락 뒤 동의 화면(P-02)의 미리보기 · 동의 (G-02, G-41) */
  guardianConsent: false,
  /**
   * 보호자: 설정 · 알림 화면의 동의 부여 · 철회 (G-41). 서버에는 있지만 끈다 — 이 화면들의
   * 아이 목록(guardianHome)이 아직 mock 이라 서버에 없는 아이 id 로 요청하게 된다.
   * guardianHome 을 켤 때 같이 켠다.
   */
  guardianLinks: false,
  /** 보호자: 초대 코드 수락 (G-04) */
  guardianInvite: true,
  /**
   * 보호자: 아이 연결 해제 (G-05). 서버에는 있지만 끈다 — 해제할 아이를 고르는 목록
   * (G-10, guardianHome)이 아직 mock 이라 서버에 없는 아이 id 로 요청하게 된다.
   * guardianHome 을 켤 때 같이 켠다.
   */
  guardianUnlink: false,
  /** 보호자: 아이 목록 · 홈 · 일지 · 리포트 · 오늘 요약 */
  guardianHome: false,
  /** 보호자: 기관 요청 알림 · 확인 · 거절 */
  institutionRequests: false,
  /** 보호자: 기관 코드로 연결 */
  institutionCode: false,
  /** 보호자: 돌봄 정보 */
  careInfo: false,
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
  return USE_MOCK || BE_READY[feature] ? load() : Promise.resolve(null);
}

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

export { ApiError };

/* ── 기관 ───────────────────────────────────────────── */

/** GET /api/v1/institutions/me/children */
export async function getChildren(): Promise<ChildListItem[]> {
  if (mockFor("children")) return mock.CHILDREN;
  return request("/api/v1/institutions/me/children");
}

/**
 * 아이 한 명. 상세 API 가 아직 없어서, 명부가 실서버면 명부에서 찾는다 — 그래야 실제로
 * 등록한 아이의 상세 화면(초대 코드 · 보호자)이 열린다. 명부에는 연결된 기관이 없다.
 */
export async function getChild(id: string): Promise<ChildListItem | undefined> {
  if (!mockFor("childProfile")) return request(`/api/v1/children/${id}`);
  if (!mockFor("children")) return (await getChildren()).find((c) => c.id === id);
  return mock.CHILDREN.find((c) => c.id === id);
}

/** GET /api/v1/children/{childId}/context */
export async function getTimeline(childId: string): Promise<TimelineEntry[]> {
  if (mockFor("childProfile")) return mock.TIMELINE[childId] ?? [];
  return request(`/api/v1/children/${childId}/context`);
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

/** GET /api/v1/validation-results?status=BLOCK */
export async function getBlockedQueue(): Promise<BlockedItem[]> {
  if (mockFor("blockedQueue")) return mock.BLOCKED_QUEUE;
  return request("/api/v1/validation-results?status=BLOCK");
}

/** GET /api/v1/summaries?gate1_status=pending */
export async function getGate1Queue(): Promise<SummaryItem[]> {
  if (mockFor("gate1")) return mock.GATE1_QUEUE;
  return request("/api/v1/summaries?gate1_status=pending");
}

/** POST /api/v1/summaries/{summaryId}/gate1 */
export async function decideGate1(
  summaryId: string,
  body: { decision: "approve" | "reject"; edited_content?: string; reason?: string },
) {
  if (mockFor("gate1")) {
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
  if (mockFor("insights")) return mock.INSIGHTS;
  return request("/api/v1/insights");
}

export async function getInsight(id: string): Promise<Insight | undefined> {
  if (mockFor("insights")) return mock.INSIGHTS.find((i) => i.id === id);
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
  if (mockFor("insights")) {
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
  if (mockFor("gate1")) {
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
 *
 * 한 파일이 실패해도 나머지는 이미 서버에 저장됐다. 그래서 전체를 실패로 던지지 않고
 * 파일마다 결과를 돌려준다 — 화면이 실패한 것만 다시 올리게 해야 원본이 중복되지 않는다.
 */
export async function uploadRawRecords(files: File[]): Promise<UploadResult[]> {
  if (mockFor("upload")) {
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
  const settled = await Promise.allSettled(files.map(uploadOne));
  return settled.map((s, i) =>
    s.status === "fulfilled"
      ? { ok: true, file: files[i], progress: s.value }
      : { ok: false, file: files[i], reason: uploadFailReasonOf(s.reason) },
  );
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
 * 상태 코드로만 가른다. nginx 가 25MB 에서 먼저 막으면 본문이 JSON 이 아니라 code 가 없다.
 * 네트워크 오류(fetch 의 TypeError)도 temporary 다.
 */
function uploadFailReasonOf(err: unknown): UploadFailReason {
  if (err instanceof ApiError && err.status === 400) return "invalid";
  if (err instanceof ApiError && err.status === 413) return "too_large";
  return "temporary";
}

/** mock 도 BE 와 같은 규칙으로 거절한다 — 실패 화면을 mock 에서 확인할 수 있게 */
const UPLOAD_EXTENSIONS = ["csv", "txt", "pdf"];
const UPLOAD_MAX_BYTES = 20 * 1024 * 1024;

function mockRejectReason(file: File): UploadFailReason | null {
  const ext = file.name.split(".").pop()?.toLowerCase() ?? "";
  if (!UPLOAD_EXTENSIONS.includes(ext)) return "invalid";
  if (file.size > UPLOAD_MAX_BYTES) return "too_large";
  return null;
}

/**
 * 파일별 처리 현황. 최근 업로드가 먼저 온다.
 *
 * GET /api/v1/raw-records/progress — **계약 확정, 구현 예정** (api-spec.md O-26)
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
 * 실패한 기록을 다시 처리한다. 실패는 사람이 고를 게 아니라 시스템 오류라 재시도로 충분하다.
 * POST /api/v1/raw-records/{id}/retry — **계약 확정, 구현 예정** (api-spec.md O-27)
 */
export async function retryFailedEntries(rawRecordId: string): Promise<{ ok: true }> {
  if (mockFor("fileProgress")) {
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
 * POST /api/v1/institutions/me/children — 아이 등록 (api-spec O-11).
 *
 * 등록과 함께 서버가 첫 초대 코드를 발급해 invitation 으로 준다. 한 트랜잭션이라
 * 503 INVITE_CODE_ISSUE_FAILED 면 아이도 등록되지 않았다 — 그대로 다시 요청하면 된다.
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
    const child: Child = {
      id: `child_${Math.random().toString(36).slice(2, 8)}`,
      name: input.name.trim(),
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

/** GET /api/v1/institutions/me/inbox */
export async function getInbox(): Promise<InboxItem[]> {
  if (mockFor("inbox")) return mock.INBOX;
  return request("/api/v1/institutions/me/inbox");
}

/** POST /api/v1/children/{childId}/chat */
export async function askChat(childId: string, question: string): Promise<ChatTurn> {
  if (mockFor("chat")) {
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
  if (mockFor("activity")) return mock.ACTIVITY;
  return request("/api/v1/audit-logs");
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
  return request("/api/v1/guardians/me/invitations/accept", {
    method: "POST",
    body: JSON.stringify({ code }),
  });
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
  return request(`/api/v1/guardians/me/children/${encodeURIComponent(childId)}`, {
    method: "DELETE",
  });
}

/**
 * GET /api/v1/children/{childId}/consent-preview?institutionId= (api-spec G-02)
 *
 * P-02 가 그릴 아이 · 기관 · 공유 범위. 동의할 것이 없으면 null 이다 — 다른 아이나
 * 기관으로 대신 채우지 않는다. 동의 화면에 엉뚱한 아이가 뜨면 잘못 동의하게 된다.
 * 이미 동의했거나 알림에서 거절한 요청도 "없는 요청" 으로 본다.
 */
export async function getConsentPreview(
  childId: string,
  institutionId: string,
): Promise<ConsentPreview | null> {
  if (mockFor("guardianConsent")) {
    const child = mock.PARENT_CHILDREN.find((c) => c.id === childId);
    const rec = child?.institutions.find(
      (i) => i.institution.id === institutionId && i.consent === "not_granted",
    );
    if (!child || !rec) return null;
    if (mock.DECLINED_INSTITUTION_REQUESTS.has(`${childId}:${institutionId}`)) return null;
    const { shared, notShared } = mock.INSTITUTION_SHARE_FIELDS[rec.institution.type];
    return {
      child: { id: child.id, name: child.name, birthDate: child.birthDate },
      institution: rec.institution,
      documentUrl: null,
      sharedFields: shared,
      notSharedFields: notShared,
      // 서버도 기관 유형과 관계없이 이 두 항목을 내려준다 (ConsentFieldCatalog)
      allowedFields: ["daily_summary", "weekly_insight"],
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

/** GET /api/v1/guardians/me/children — 이 보호자에게 연결된 아이 전체 */
export async function getParentChildren(): Promise<Child[]> {
  if (mockFor("guardianHome")) return mock.PARENT_CHILDREN;
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
  if (mockFor("guardianHome")) {
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
  if (mockFor("institutionRequests")) {
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
  if (mockFor("institutionRequests")) {
    mock.DECLINED_INSTITUTION_REQUESTS.add(`${childId}:${institutionId}`);
    return { ok: true };
  }
  return request(`/api/v1/children/${childId}/institution-requests/${institutionId}/decline`, {
    method: "POST",
  });
}

/**
 * PUT /api/v1/children/{childId}/consent-scopes/{institutionId} — P-02 의 동의 (api-spec G-41).
 *
 * allowedFields 는 미리보기(G-02)가 준 코드를 그대로 보낸다. 화면 문구(sharedFields)를
 * 보내면 서버가 모르는 항목이라 400 으로 거절한다.
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
  return request(
    `/api/v1/children/${encodeURIComponent(childId)}/consent-scopes/${encodeURIComponent(institutionId)}`,
    {
      method: "PUT",
      body: JSON.stringify({ action: "grant", allowedFields }),
    },
  );
}

/**
 * PUT /api/v1/children/{childId}/consent-scopes/{institutionId} — 설정 · 알림 화면용.
 * 아직 mock 만 쓴다(guardianLinks). 본문 모양이 서버(allowedFields, 코드값)와 달라
 * 켜기 전에 grantConsent 처럼 맞춰야 한다.
 */
export async function updateConsent(
  childId: string,
  institutionId: string,
  body: { allowed_fields: string[]; action: "grant" | "revoke" },
) {
  if (mockFor("guardianLinks")) {
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
  if (mockFor("institutionCode")) return code.trim() ? mock.ART_ACADEMY : null;
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
  if (mockFor("institutionCode")) {
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
  if (mockFor("careInfo")) {
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
  if (mockFor("guardianHome")) {
    return mock.JOURNAL.filter((j) => j.childId === childId).sort((a, b) =>
      `${b.date}${b.time}`.localeCompare(`${a.date}${a.time}`),
    );
  }
  return request(`/api/v1/children/${childId}/journal`);
}

export async function getJournalEntry(id: string): Promise<JournalEntry | undefined> {
  if (mockFor("guardianHome")) return mock.JOURNAL.find((j) => j.id === id);
  return request(`/api/v1/journal/${id}`);
}

/** POST /api/v1/journal/{id}/flag — "이 내용이 이상해요": 기관에 재확인 요청 */
export async function flagJournalEntry(id: string) {
  if (mockFor("guardianHome")) {
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
  if (mockFor("guardianHome")) return mock.CARE_REPORTS[childId]?.[period] ?? null;
  return request(`/api/v1/children/${childId}/care-report?period=${period}`);
}

/** GET /api/v1/children/{childId}/institution-requests */
export async function getInstitutionRequests(childId: string): Promise<InstitutionRequestItem[]> {
  if (mockFor("institutionRequests")) return mock.INSTITUTION_REQUESTS[childId] ?? [];
  return request(`/api/v1/children/${childId}/institution-requests`);
}

/** 타임라인/홈 상단 TODAY 카드 — 아이별. 없으면 null (화면에서 숨김) */
export async function getTodaySummary(childId: string): Promise<string | null> {
  if (mockFor("guardianHome")) return mock.PARENT_TODAY_SUMMARY[childId] ?? null;
  return request(`/api/v1/children/${childId}/today-summary`);
}

/** POST /api/v1/institution-requests/{id}/confirm */
export async function confirmInstitutionRequest(id: string) {
  if (mockFor("institutionRequests")) {
    const req = Object.values(mock.INSTITUTION_REQUESTS)
      .flat()
      .find((r) => r.id === id);
    if (req) req.status = "confirmed";
    return { ok: true };
  }
  return request(`/api/v1/institution-requests/${id}/confirm`, { method: "POST" });
}
