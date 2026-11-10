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
  JournalEntryDetail,
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

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "false";
const BASE = import.meta.env.VITE_API_BASE_URL ?? "";

// 기능 이름. 예전에는 백엔드가 준비된 기능만 실연동하는 스위치 표(BE_READY)의 키였다.
// 모든 기능이 준비돼 표를 지웠고, 호출하는 쪽을 바꾸지 않으려고 이름과 아래 함수 모양만 남긴다.
export type Feature =
  | "children"
  | "childRegister"
  | "invitations"
  | "childGuardians"
  | "childTimeline"
  | "matchingQueue"
  | "blockedQueue"
  | "gate1"
  | "dashboard"
  | "summaryFailures"
  | "insights"
  | "upload"
  | "fileProgress"
  | "inbox"
  | "institutionInfo"
  | "linkRequests"
  | "guardianConsent"
  | "guardianLinks"
  | "guardianInvite"
  | "guardianUnlink"
  | "guardianChildren"
  | "guardianJournal"
  | "consentRequests"
  | "checklists"
  | "institutionCode"
  | "guardianInsights"
  | "institutionShares"
  | "careInfo";

// mock 은 VITE_USE_MOCK 하나로만 정한다.
function mockFor(_feature: Feature): boolean {
  return USE_MOCK;
}

export function loadIfReady<T>(_feature: Feature, load: () => Promise<T>): Promise<T | null> {
  return load();
}

export function isReady(_feature: Feature): boolean {
  return true;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  return (await requestWithHeaders<T>(path, init)).data;
}

async function requestWithHeaders<T>(path: string, init?: RequestInit): Promise<{ data: T; headers: Headers }> {
  const method = (init?.method ?? "GET").toUpperCase();
  const needsCsrf = method !== "GET" && method !== "HEAD";
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
    if (res.status === 401) clearSession();
    const body = await res.json().catch(() => ({}));
    const error = new ApiError(res.status, body.code ?? "UNKNOWN", body.message);
    if (error.status === 403 && error.code === "TERMS_NOT_AGREED") void sendToTerms();
    throw error;
  }
  if (res.status === 204) return { data: undefined as T, headers: res.headers };

  const body = await res.json();
  return { data: body.data as T, headers: res.headers };
}

export { ApiError };

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

function rosterItemOf(child: Child): ChildListItem | null {
  const mine = child.institutions.find((i) => i.institution.id === mock.MY_INSTITUTION.id);
  if (!mine) return null;
  const { id, name, birthDate, externalId, status } = child;
  return { id, name, birthDate, externalId, status, consent: mine.consent };
}

function onMyRoster(childId: string): boolean {
  const child = mock.CHILDREN.find((c) => c.id === childId);
  return !!child && rosterItemOf(child) !== null;
}

function rosterRemoved(childId: string, institutionId: string): boolean {
  return mock.ROSTER_REMOVED.some((r) => r.childId === childId && r.institutionId === institutionId);
}

function clearRosterRemoved(childId: string, institutionId: string, fields: ConsentField[] | undefined) {
  if (!fields?.includes("weekly_insight")) return;
  for (let i = mock.ROSTER_REMOVED.length - 1; i >= 0; i--) {
    const r = mock.ROSTER_REMOVED[i];
    if (r.childId === childId && r.institutionId === institutionId) mock.ROSTER_REMOVED.splice(i, 1);
  }
}

export async function getChildren(): Promise<ChildListItem[]> {
  if (mockFor("children")) {
    return mock.CHILDREN.map(rosterItemOf).filter((c): c is ChildListItem => c !== null);
  }
  return request("/api/v1/institutions/me/children");
}

export async function getChild(id: string): Promise<ChildListItem | undefined> {
  return (await getChildren()).find((c) => c.id === id);
}

export async function getMyInstitution(): Promise<MyInstitution> {
  if (mockFor("institutionInfo")) return { ...mock.MY_INSTITUTION, code: mock.MY_INSTITUTION_CODE };
  return request("/api/v1/institutions/me");
}

export interface IssuedHandover {
  code: string;
  expiresAt: string;
}

export async function issueHandover(): Promise<IssuedHandover> {
  return request("/api/v1/institutions/me/handover", { method: "POST" });
}

export async function cancelHandover(): Promise<void> {
  await request("/api/v1/institutions/me/handover", { method: "DELETE" });
}

export async function getLinkRequests(): Promise<PendingLinkRequest[]> {
  if (mockFor("linkRequests")) return mock.LINK_REQUESTS;
  return request("/api/v1/institutions/me/link-requests");
}

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

export async function getTimeline(childId: string): Promise<TimelineEntry[]> {
  if (mockFor("childTimeline")) return mock.TIMELINE[childId] ?? [];
  return request(`/api/v1/institutions/me/children/${encodeURIComponent(childId)}/timeline`);
}

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
  reviewFlags: { journalEntryId: number; issueTypes: string[]; reasons?: string[]; claimIndexes?: number[] | null }[] | null;
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
      reasons: (f.reasons ?? f.issueTypes ?? []).filter((r) => r.trim() !== ""),
      claimIndexes: f.claimIndexes ?? [],
    })),
  };
}

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

export async function decideGate1(
  summaryId: string,
  body: { decision: Gate1Decision; cards?: Gate1Card[]; version: string },
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

interface PipelineRaw {
  turn: Pipeline["turn"];
  rows: (Omit<PipelineRow, "childId" | "target"> & {
    childId: string | number | null;
    target: { type: NonNullable<PipelineRow["target"]>["type"]; id?: string | number | null } | null;
  })[];
}

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

export async function getAiActivity(): Promise<AiActivity> {
  if (mockFor("dashboard")) return { ...mock.AI_ACTIVITY };
  return request<AiActivity>("/api/v1/dashboard/ai-activity");
}

export async function getAiStatus(): Promise<AiStatus> {
  if (mockFor("dashboard")) return { ...mock.AI_STATUS };
  return request<AiStatus>("/api/v1/system/ai-status");
}

export async function getAiProgress(): Promise<AiProgress> {
  if (mockFor("dashboard")) return mock.mockAiProgress();
  const raw = await request<AiProgress>("/api/v1/dashboard/ai-progress");
  return {
    enabled: raw.enabled === true,
    items: (raw.items ?? []).map((i) => ({ ...i, childId: i.childId == null ? null : String(i.childId) })),
  };
}

interface FailedSummaryRaw {
  childId: number;
  childName: string;
  entryDate: string;
  entries: { journalEntryId: number; content: string }[] | null;
  reason: string;
}

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

export async function retryFailedSummary(childId: string, entryDate: string): Promise<void> {
  if (mockFor("summaryFailures")) return dropMockFailed(childId, entryDate);
  await request("/api/v1/summaries/failed/retry", { method: "POST", body: failedBody(childId, entryDate) });
}

export async function closeFailedSummary(childId: string, entryDate: string): Promise<void> {
  if (mockFor("summaryFailures")) return dropMockFailed(childId, entryDate);
  await request("/api/v1/summaries/failed/close", { method: "POST", body: failedBody(childId, entryDate) });
}

export async function getInsights(gate2Status: Gate2Status = "pending"): Promise<Insight[]> {
  if (mockFor("insights")) {
    return mock.INSIGHTS.filter(
      (i) =>
        i.primarySource.id === mock.MY_INSTITUTION.id &&
        i.gate2Status === gate2Status &&
        onMyRoster(i.childId) &&
        !i.evidence.some((e) => rosterRemoved(i.childId, e.institution.id)),
    );
  }
  return request(`/api/v1/insights?gate2Status=${gate2Status}`);
}

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

export async function uploadRawRecords(files: File[]): Promise<UploadResult[]> {
  if (mockFor("upload")) {
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
    const skip = uploadPrecheck(file);
    if (skip) return { ok: false, file, reason: skip };
    try {
      return { ok: true, file, progress: await uploadOne(file) };
    } catch (err) {
      return { ok: false, file, reason: uploadFailReasonOf(err) };
    }
  });
}

const UPLOAD_CONCURRENCY = 2;

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

interface UploadTarget {
  uploadId: number;
  uploadUrl: string;
  method: string;
  headers: Record<string, string>;
  expiresAt: string;
}

class StorageUploadError extends Error {
  constructor(readonly status: number) {
    super(`storage did not accept the upload (${status})`);
  }
}

const COMPLETE_RETRY_DELAYS_MS = [1000, 3000];

async function uploadOne(file: File): Promise<FileProgress> {
  for (let attempt = 1; ; attempt++) {
    const target = await request<UploadTarget>("/api/v1/raw-records/upload-url", {
      method: "POST",
      body: JSON.stringify({
        filename: file.name,
        contentType: file.type || "application/octet-stream",
        size: file.size,
      }),
    });
    try {
      await putToStorage(target, file);
      const saved = await completeUpload(target.uploadId);
      return {
        rawRecordId: String(saved.id),
        fileName: saved.originalFilename,
        uploadedAt: saved.createdAt,
        entries: [],
      };
    } catch (err) {
      const startOver =
        err instanceof StorageUploadError || (err instanceof ApiError && err.code === "RAW_RECORD_UPLOAD_NOT_READY");
      if (attempt < 2 && startOver) continue;
      throw err;
    }
  }
}

async function putToStorage(target: UploadTarget, file: File): Promise<void> {
  const toBackend = target.uploadUrl.startsWith("/");
  let res: Response;
  try {
    res = await fetch(toBackend ? `${BASE}${target.uploadUrl}` : target.uploadUrl, {
      method: target.method,
      body: file,
      headers: { ...target.headers, ...(toBackend ? csrfHeader() : {}) },
      credentials: toBackend ? "include" : "omit",
      cache: "no-store",
    });
  } catch {
    throw new StorageUploadError(0);
  }
  if (res.ok) return;
  if (toBackend) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body.code ?? "UNKNOWN", body.message);
  }
  throw new StorageUploadError(res.status);
}

async function completeUpload(uploadId: number): Promise<{ id: number; originalFilename: string; createdAt: string }> {
  for (let retry = 0; ; retry++) {
    try {
      return await request(`/api/v1/raw-records/uploads/${uploadId}/complete`, { method: "POST" });
    } catch (err) {
      if (retry >= COMPLETE_RETRY_DELAYS_MS.length || !isRetryableComplete(err)) throw err;
      await new Promise((resolve) => setTimeout(resolve, COMPLETE_RETRY_DELAYS_MS[retry]));
    }
  }
}

function isRetryableComplete(err: unknown): boolean {
  if (err instanceof ApiError) {
    return err.status >= 500 || err.code === "RAW_RECORD_UPLOAD_BUSY" || err.code === "RAW_RECORD_UPLOAD_NOT_READY";
  }
  return err instanceof TypeError;
}

function uploadFailReasonOf(err: unknown): UploadFailReason {
  if (err instanceof ApiError && err.code === "RAW_RECORD_ENTRY_LIMIT_EXCEEDED") return "too_many_entries";
  if (isAgentQueueFull(err)) return "queue_full";
  if (err instanceof ApiError && err.code === "RAW_RECORD_UPLOAD_BUSY") return "server_busy";
  if (err instanceof ApiError && err.status === 400) return "invalid";
  if (err instanceof ApiError && err.status === 413) return "too_large";
  return "temporary";
}

export const UPLOAD_EXTENSIONS = ["csv", "txt", "docx", "pdf"];
const UPLOAD_MAX_BYTES = 20 * 1024 * 1024;

export function uploadPrecheck(file: File): "invalid" | "empty" | "too_large" | null {
  if (!hasUploadExtension(file)) return "invalid";
  if (file.size === 0) return "empty";
  if (file.size > UPLOAD_MAX_BYTES) return "too_large";
  return null;
}

function hasUploadExtension(file: File): boolean {
  const dot = file.name.lastIndexOf(".");
  const ext = dot < 0 ? "" : file.name.slice(dot + 1).toLowerCase();
  return UPLOAD_EXTENSIONS.includes(ext);
}

function mockRejectReason(file: File): UploadFailReason | null {
  const skip = uploadPrecheck(file);
  if (skip) return skip;
  if (file.name.includes("많음")) return "too_many_entries";
  if (file.name.includes("대기")) return "queue_full";
  if (file.name.includes("혼잡")) return "server_busy";
  return null;
}

export function isAgentQueueFull(err: unknown): boolean {
  return err instanceof ApiError && err.code === "AGENT_QUEUE_FULL";
}

export const AGENT_QUEUE_FULL_TEXT = "앞서 올린 기록을 처리하고 있어요. 끝나면 다시 해 주세요.";

export async function getFileProgress(): Promise<FileProgress[]> {
  if (mockFor("fileProgress")) {
    tickSimulation();
    return mock.FILE_PROGRESS;
  }
  return request("/api/v1/raw-records/progress");
}

export interface UploadedFile {
  fileName: string;
  uploadedAt: string;
}

export async function getUploadedFiles(): Promise<UploadedFile[]> {
  if (mockFor("fileProgress")) {
    return mock.FILE_PROGRESS.map((f) => ({ fileName: f.fileName, uploadedAt: f.uploadedAt }));
  }
  const rows = await request<{ originalFilename: string; createdAt: string; status: string }[]>(
    "/api/v1/raw-records",
  );
  return rows
    .filter((r) => r.status !== "FAILED")
    .map((r) => ({ fileName: r.originalFilename, uploadedAt: r.createdAt }));
}

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

export const MAX_REVISED_CONTENT = 5_000;

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

export async function registerChild(input: {
  name: string;
  birthDate: string;
  externalId: string;
}): Promise<{ child: RegisteredChild; invitation: IssuedInvitation }> {
  if (mockFor("childRegister")) {
    const roster = mock.CHILDREN.filter((c) => rosterItemOf(c) !== null);
    const externalId = input.externalId.trim();
    if (roster.some((c) => c.externalId === externalId)) {
      throw new ApiError(409, "DUPLICATE_EXTERNAL_ID");
    }
    const trimmedName = input.name.trim();
    if (roster.some((c) => c.name === trimmedName && c.birthDate === input.birthDate)) {
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
    expiresAt: new Date(now + mock.INVITE_TTL_MS).toISOString(),
  };
  (mock.INVITATIONS[childId] ??= []).unshift(invitation);
  const { id, expiresAt } = invitation;
  return { id, code, expiresAt };
}

const childPath = (childId: string) =>
  `/api/v1/institutions/me/children/${encodeURIComponent(childId)}`;

export async function removeChildFromRoster(childId: string): Promise<void> {
  if (mockFor("children")) {
    const child = mock.CHILDREN.find((c) => c.id === childId);
    const idx = child ? child.institutions.findIndex((i) => i.institution.id === mock.MY_INSTITUTION.id) : -1;
    if (!child || idx === -1) throw new ApiError(404, "CHILD_NOT_FOUND");
    child.institutions.splice(idx, 1);
    child.externalId = null;
    delete mock.INVITATIONS[childId];
    mock.ROSTER_REMOVED.push({ childId, institutionId: mock.MY_INSTITUTION.id });
    for (let i = mock.INBOX.length - 1; i >= 0; i--) {
      if (mock.INBOX[i].childId === childId) mock.INBOX.splice(i, 1);
    }
    if (child.status !== "pending_consent") {
      child.status = child.institutions.some((i) => i.consent === "granted") ? "active" : "suspended";
    }
    return;
  }
  return request(childPath(childId), { method: "DELETE" });
}

export async function issueInvitation(childId: string): Promise<IssuedInvitation> {
  if (mockFor("invitations")) {
    if (!onMyRoster(childId)) throw new ApiError(404, "CHILD_NOT_FOUND");
    return issueMockInvitation(childId);
  }
  return request(`${childPath(childId)}/invitations`, { method: "POST" });
}

export async function getLiveInvitations(childId: string): Promise<LiveInvitation[]> {
  if (mockFor("invitations")) {
    const now = Date.now();
    return (mock.INVITATIONS[childId] ?? []).filter((i) => Date.parse(i.expiresAt) > now);
  }
  return request(`${childPath(childId)}/invitations`);
}

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

export async function getChildGuardians(childId: string): Promise<ChildGuardian[]> {
  if (mockFor("childGuardians")) return mock.CHILD_GUARDIANS[childId] ?? [];
  return request(`${childPath(childId)}/guardians`);
}

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

export async function getInbox(): Promise<InboxItem[]> {
  if (mockFor("inbox")) {
    return mock.INBOX.filter((i) => onMyRoster(i.childId)).map(({ content: _content, ...item }) => ({ ...item }));
  }
  return request("/api/v1/institutions/me/inbox");
}

export async function getInboxUnreadCount(): Promise<number> {
  if (mockFor("inbox")) {
    return mock.INBOX.filter((i) => onMyRoster(i.childId) && !i.readAt).length;
  }
  const { unread } = await request<{ unread: number }>("/api/v1/institutions/me/inbox/unread-count");
  return unread;
}

export async function getInboxItem(insightId: string): Promise<InboxDetail> {
  if (mockFor("inbox")) {
    const item = mock.INBOX.find((i) => i.id === insightId);
    if (!item || !onMyRoster(item.childId)) throw new ApiError(404, "SHARING_NOT_FOUND", "인사이트를 찾을 수 없습니다.");
    item.readAt ??= new Date().toISOString();
    return { ...item };
  }
  return request(`/api/v1/institutions/me/inbox/${encodeURIComponent(insightId)}`);
}

export async function acceptInvitation(code: string): Promise<AcceptedInvitation> {
  if (mockFor("guardianInvite")) {
    const normalized = code.replace(/[\s-]/g, "").toUpperCase();
    const now = Date.now();
    const issued = Object.entries(mock.INVITATIONS).find(([, invitations]) =>
      invitations.some((invitation) => invitation.code === normalized && Date.parse(invitation.expiresAt) > now),
    );
    if (!issued) throw new ApiError(400, "INVALID_INVITE_CODE");

    const [childId, invitations] = issued;
    const child = mock.CHILDREN.find((c) => c.id === childId);
    const mine = child?.institutions.find((i) => i.institution.id === mock.MY_INSTITUTION.id);
    if (!child || !mine) throw new ApiError(400, "INVALID_INVITE_CODE");
    if (mock.PARENT_CHILDREN.includes(child)) throw new ApiError(409, "LINK_ALREADY_EXISTS");

    mock.INVITATIONS[childId] = invitations.filter((invitation) => invitation.code !== normalized);
    mock.PARENT_CHILDREN.push(child);
    const institution = mine.institution;
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
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export async function getParentChildren(): Promise<Child[]> {
  return (await getParentChildrenWithOwner()).children;
}

export type ParentChildrenWithOwner = { children: Child[]; ownerUserId: string | null };

export async function getParentChildrenWithOwner(): Promise<ParentChildrenWithOwner> {
  if (mockFor("guardianChildren")) {
    const children = mock.PARENT_CHILDREN.map((child) => ({
      ...child,
      hasUnreadInsight: mock.GUARDIAN_INSIGHTS.some((i) => i.childId === child.id && i.kind !== "quiet" && !i.readAt),
    }));
    return { children, ownerUserId: isAuthMock() ? "mock" : null };
  }
  return shareParentChildren(async () => {
    const { data, headers } = await requestWithHeaders<Child[]>("/api/v1/guardians/me/children");
    return { children: data, ownerUserId: headers.get("X-Itda-User-Id") };
  });
}

async function changesParentChildren<T>(send: Promise<T>): Promise<T> {
  try {
    return await send;
  } finally {
    forgetParentChildren();
  }
}

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
    clearRosterRemoved(childId, institutionId, allowedFields);
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

export const ALL_CONSENT_FIELDS: ConsentField[] = ["daily_summary", "weekly_insight"];

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
    if (body.action === "grant") clearRosterRemoved(childId, institutionId, body.allowedFields);
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

export async function lookupInstitutionByCode(code: string): Promise<Institution | null> {
  if (mockFor("institutionCode")) return code.trim() ? mock.ART_ACADEMY : null;
  try {
    return await request(`/api/v1/institutions/lookup?code=${encodeURIComponent(code)}`);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return null;
    throw err;
  }
}

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

export async function getJournal(childId: string, { limit }: { limit?: number } = {}): Promise<JournalEntry[]> {
  if (mockFor("guardianJournal")) {
    const all = mock.JOURNAL.filter((j) => j.childId === childId).sort((a, b) => b.date.localeCompare(a.date));
    return limit === undefined ? all : all.slice(0, limit);
  }
  const query = limit === undefined ? "" : `?limit=${limit}`;
  return request(`/api/v1/children/${encodeURIComponent(childId)}/journal${query}`);
}

export async function getJournalEntry(id: string): Promise<JournalEntryDetail | undefined> {
  if (mockFor("guardianJournal")) {
    const entry = mock.JOURNAL.find((j) => j.id === id);
    if (!entry) return undefined;
    const journal = await getJournal(entry.childId);
    const idx = journal.findIndex((j) => j.id === id);
    return {
      ...entry,
      nextId: idx > 0 ? journal[idx - 1].id : null,
      prevId: idx < journal.length - 1 ? journal[idx + 1].id : null,
      sameDay: journal.filter((j) => j.date === entry.date && j.institution.id !== entry.institution.id),
    };
  }
  try {
    return await request<JournalEntryDetail>(`/api/v1/journal/${encodeURIComponent(id)}`);
  } catch (err) {
    if (err instanceof ApiError && err.code === "JOURNAL_NOT_FOUND") return undefined;
    throw err;
  }
}

export async function getGuardianInsights(childId: string): Promise<GuardianInsight[]> {
  if (mockFor("guardianInsights")) {
    return mock.GUARDIAN_INSIGHTS.filter((i) => i.childId === childId).map((insight) => ({ ...insight }));
  }
  return request(`/api/v1/guardians/me/insights?childId=${encodeURIComponent(childId)}`);
}

export async function getInstitutionShares(childId: string): Promise<InstitutionShares[]> {
  if (mockFor("institutionShares")) return mock.INSTITUTION_SHARES[childId] ?? [];
  return request(`/api/v1/guardians/me/children/${encodeURIComponent(childId)}/institution-shares`);
}

export async function getGuardianInsight(id: string): Promise<GuardianInsight | undefined> {
  if (mockFor("guardianInsights")) {
    const insight = mock.GUARDIAN_INSIGHTS.find((i) => i.id === id);
    if (!insight) return undefined;
    insight.readAt ??= new Date().toISOString();
    return { ...insight };
  }
  try {
    const insight = await request<GuardianInsight>(`/api/v1/guardians/me/insights/${encodeURIComponent(id)}`);
    forgetParentChildren();
    return insight;
  } catch (err) {
    if (err instanceof ApiError && err.code === "SHARING_NOT_FOUND") return undefined;
    throw err;
  }
}

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

export async function loadOpenChecklistCount(childId: string): Promise<number | null> {
  if (!isReady("checklists")) return null;
  try {
    return (await getChecklists(childId, { needsCheckOnly: true })).length;
  } catch (err) {
    if (err instanceof ApiError && (err.status === 401 || err.code === "TERMS_NOT_AGREED")) throw err;
    return null;
  }
}

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

export async function getSentChecklists(before?: string): Promise<SentChecklistPage> {
  if (mockFor("checklists")) {
    const live = mock.SENT_CHECKLISTS;
    const start = before ? live.findIndex((c) => c.id === before) + 1 : 0;
    return { checklists: live.slice(start, start + 50), nextBefore: live.length > start + 50 ? live[start + 49].id : null };
  }
  const query = before ? `?before=${encodeURIComponent(before)}` : "";
  return request(`/api/v1/institutions/me/checklists${query}`);
}

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

export async function cancelChecklist(id: string): Promise<void> {
  if (mockFor("checklists")) {
    const at = mock.SENT_CHECKLISTS.findIndex((c) => c.id === id);
    if (at >= 0) mock.SENT_CHECKLISTS.splice(at, 1);
    return;
  }
  return request(`/api/v1/institutions/me/checklists/${encodeURIComponent(id)}`, { method: "DELETE" });
}
