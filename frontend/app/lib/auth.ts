import { ApiError } from "@/lib/apiError";
import { clearGate1Drafts } from "@/lib/gate1Draft";
import { clearReinputDrafts } from "@/lib/reinputDraft";
import { MOCK_TERMS } from "@/lib/mock/terms";
import { forgetParentChildren } from "@/lib/parentChildrenCache";

const USE_MOCK = import.meta.env.VITE_AUTH_MOCK !== "false";

const AUTH_ORIGIN = import.meta.env.VITE_AUTH_ORIGIN ?? "";
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "";

const ROLE_KEY = "itda_role";

const ONBOARDED_KEY = "itda_parent_onboarded";

const TERMS_KEY = "itda_parent_terms";

const INTENT_KEY = "itda_login_intent";

const INVITE_CODE_KEY = "itda_invite_code";

const RETURN_PATH_KEY = "itda_return_path";

const LOGIN_STARTED_KEY = "itda_login_started";
const LOGIN_STARTED_TTL_MS = 30 * 60 * 1000;

export type Role = "org" | "parent" | null;

export type Session = {
  loggedIn: boolean;
  signupCompleted: boolean;
  role: Role;
  userId?: string;
  institutionName?: string;
  termsAgreed: boolean;
  withdrawing: boolean;
  withdrawn: boolean;
  withdrawalEnabled: boolean;
};

const ANONYMOUS: Session = {
  loggedIn: false,
  signupCompleted: false,
  role: null,
  termsAgreed: false,
  withdrawing: false,
  withdrawn: false,
  withdrawalEnabled: false,
};

type MeResponse = {
  signupCompleted: boolean;
  userId: string;
  role?: "org" | "parent";
  institutionId?: string;
  institutionName?: string;
  termsAgreed: boolean;
  withdrawal?: "none" | "in_progress";
  withdrawalEnabled?: boolean;
};

function toSession(me: MeResponse): Session {
  return {
    loggedIn: true,
    signupCompleted: me.signupCompleted,
    role: me.signupCompleted ? (me.role ?? null) : null,
    userId: me.userId,
    institutionName: me.institutionName,
    termsAgreed: me.signupCompleted && me.termsAgreed === true,
    withdrawing: me.withdrawal === "in_progress",
    withdrawn: false,
    withdrawalEnabled: me.withdrawalEnabled === true,
  };
}

async function toApiError(res: Response): Promise<ApiError> {
  const body = await res.json().catch(() => ({}));
  return new ApiError(res.status, body.code ?? "UNKNOWN", body.message);
}

function readRole(): Role {
  try {
    const role = localStorage.getItem(ROLE_KEY);
    return role === "org" || role === "parent" ? role : null;
  } catch {
    return null;
  }
}

export function grantRole(role: "org" | "parent"): void {
  if (!USE_MOCK) return;
  try {
    localStorage.setItem(ROLE_KEY, role);
  } catch {
  }
}

export function clearSession(): void {
  forgetParentChildren();
  try {
    localStorage.removeItem(ROLE_KEY);
    localStorage.removeItem(ONBOARDED_KEY);
    localStorage.removeItem(TERMS_KEY);
  } catch {
  }
}

export function markOnboarded(): void {
  try {
    localStorage.setItem(ONBOARDED_KEY, "1");
  } catch {
  }
}

export function isOnboarded(): boolean {
  try {
    return localStorage.getItem(ONBOARDED_KEY) === "1";
  } catch {
    return false;
  }
}

export async function getSession(): Promise<Session> {
  if (USE_MOCK) {
    const role = readRole();
    return role
      ? {
          ...ANONYMOUS,
          loggedIn: true,
          signupCompleted: true,
          role,
          termsAgreed: role === "org" || readTermsMark(),
          userId: "mock",
          withdrawalEnabled: true,
        }
      : ANONYMOUS;
  }

  const res = await fetch(`${API_BASE}/api/v1/auth/me`, {
    credentials: "include",
    cache: "no-store",
  });
  if (res.status === 401) {
    clearSession();
    const body = await res.json().catch(() => ({}));
    return { ...ANONYMOUS, withdrawn: body?.code === "ACCOUNT_WITHDRAWN" };
  }
  if (!res.ok) throw await toApiError(res);
  const body = await res.json();
  const session = toSession(body.data as MeResponse);
  documentUserId ??= session.userId;
  return session;
}

let documentUserId: string | undefined;

export function homePathFor(role: "org" | "parent", termsAgreed: boolean): string {
  if (role === "parent") {
    return termsAgreed && isOnboarded() && !readInviteCode() ? "/parent" : "/parent/invite";
  }
  return termsAgreed ? "/dashboard" : "/org-terms";
}

export type TermKey = "tos" | "privacy" | "org_tos" | "org_privacy" | "org_child_data";

export interface TermsDocument {
  key: TermKey;
  title: string;
  version: string;
  effectiveDate: string;
  draft: boolean;
  body: string;
}

export async function getTerms(role: "parent" | "org"): Promise<TermsDocument[]> {
  if (USE_MOCK) return MOCK_TERMS.filter((t) => t.key.startsWith("org_") === (role === "org"));

  const res = await fetch(`${API_BASE}/api/v1/terms?role=${role}`, { cache: "no-store" });
  if (!res.ok) throw await toApiError(res);
  const body = await res.json();
  return body.data as TermsDocument[];
}

function readTermsMark(): boolean {
  try {
    return localStorage.getItem(TERMS_KEY) === "1";
  } catch {
    return false;
  }
}

export async function agreeTerms(agreed: { key: TermKey; version: string }[]): Promise<void> {
  if (USE_MOCK) {
    try {
      localStorage.setItem(TERMS_KEY, "1");
    } catch {
    }
    return;
  }

  const res = await fetch(`${API_BASE}/api/v1/auth/terms`, {
    method: "POST",
    credentials: "include",
    cache: "no-store",
    headers: { "content-type": "application/json", ...csrfHeader() },
    body: JSON.stringify({
      agreed: agreed.map((t) => t.key),
      versions: Object.fromEntries(agreed.map((t) => [t.key, t.version])),
    }),
  });
  if (!res.ok) {
    if (res.status === 401) clearSession();
    throw await toApiError(res);
  }
}

export function saveInviteCode(code: string): void {
  try {
    sessionStorage.setItem(INVITE_CODE_KEY, code);
  } catch {
  }
}

export function readInviteCode(): string | null {
  try {
    return sessionStorage.getItem(INVITE_CODE_KEY);
  } catch {
    return null;
  }
}

export function clearInviteCode(): void {
  try {
    sessionStorage.removeItem(INVITE_CODE_KEY);
  } catch {
  }
}

export function isAuthMock(): boolean {
  return USE_MOCK;
}

export type OrganizationType = "SCHOOL" | "CENTER" | "ACTIVITY_SUPPORT";

export type SignupInput =
  | { role: "parent" }
  | {
      role: "org";
      organizationName: string;
      organizationType: OrganizationType;
      businessNumber: string;
    }
  | {
      role: "org";
      handoverCode: string;
    };

export async function signup(input: SignupInput): Promise<Session> {
  if (USE_MOCK) {
    grantRole(input.role);
    return {
      ...ANONYMOUS,
      loggedIn: true,
      signupCompleted: true,
      role: input.role,
      termsAgreed: input.role === "org" || readTermsMark(),
    };
  }

  const body =
    input.role === "parent"
      ? { role: "parent" }
      : "handoverCode" in input
        ? { role: "org", handoverCode: input.handoverCode }
        : {
            role: "org",
            organizationName: input.organizationName,
            organizationType: input.organizationType,
            businessNumber: input.businessNumber,
          };

  const res = await fetch(`${API_BASE}/api/v1/auth/signup`, {
    method: "POST",
    credentials: "include",
    cache: "no-store",
    headers: { "content-type": "application/json", ...csrfHeader() },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    if (res.status === 401) clearSession();
    throw await toApiError(res);
  }
  const json = await res.json();
  return toSession(json.data as MeResponse);
}

export function startKakaoLogin(intent: "org" | "parent"): void {
  try {
    sessionStorage.setItem(INTENT_KEY, intent);
  } catch {
  }
  try {
    sessionStorage.setItem(LOGIN_STARTED_KEY, String(Date.now()));
  } catch {
  }
  window.location.href = `${AUTH_ORIGIN}/oauth2/authorization/kakao`;
}

export function takeLoginIntent(): "org" | "parent" | null {
  try {
    const intent = sessionStorage.getItem(INTENT_KEY);
    sessionStorage.removeItem(INTENT_KEY);
    return intent === "org" || intent === "parent" ? intent : null;
  } catch {
    return null;
  }
}

export function rememberReturnPath(role: "org" | "parent", url: URL): void {
  try {
    sessionStorage.setItem(RETURN_PATH_KEY, JSON.stringify({ role, path: url.pathname + url.search }));
  } catch {
  }
}

export function hasReturnPath(): boolean {
  try {
    return sessionStorage.getItem(RETURN_PATH_KEY) !== null;
  } catch {
    return false;
  }
}

export function takeReturnPath(role: Role): string | null {
  try {
    const raw = sessionStorage.getItem(RETURN_PATH_KEY);
    sessionStorage.removeItem(RETURN_PATH_KEY);
    if (!raw) return null;
    const saved = JSON.parse(raw) as { role?: unknown; path?: unknown };
    if (saved.role !== role || typeof saved.path !== "string") return null;
    if (!saved.path.startsWith("/") || saved.path.startsWith("//")) return null;
    return saved.path;
  } catch {
    return null;
  }
}

export async function signOut(): Promise<void> {
  clearSession();
  clearGate1Drafts();
  clearReinputDrafts();
  if (USE_MOCK) return;

  try {
    await fetch(`${API_BASE}/api/v1/auth/logout`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeader(),
    });
  } catch {
  }
}

const LOGIN_MARK_KEY = "itda_login_mark";

function readLoginMark(): string | null {
  try {
    return localStorage.getItem(LOGIN_MARK_KEY);
  } catch {
    return null;
  }
}

function loginMarkUserId(mark: string | null): string | null {
  if (!mark) return null;
  try {
    const parsed = JSON.parse(mark) as { userId?: unknown };
    return typeof parsed.userId === "string" ? parsed.userId : null;
  } catch {
    return null;
  }
}

function takeLoginStarted(): boolean {
  try {
    const raw = sessionStorage.getItem(LOGIN_STARTED_KEY);
    sessionStorage.removeItem(LOGIN_STARTED_KEY);
    const startedAt = Number(raw);
    return raw !== null && Number.isFinite(startedAt) && Date.now() - startedAt < LOGIN_STARTED_TTL_MS;
  } catch {
    return false;
  }
}

let loginLandingChecked = false;

export function announceLoginIfLanded(userId: string | undefined): void {
  if (loginLandingChecked) return;
  loginLandingChecked = true;
  const started = takeLoginStarted();
  try {
    const entry = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming | undefined;
    if (!entry || new URL(entry.name).pathname !== "/oauth/success") return;
  } catch {
    return;
  }
  if (started) announceLogin(userId);
}

function announceLogin(userId: string | undefined): void {
  try {
    localStorage.setItem(
      LOGIN_MARK_KEY,
      JSON.stringify({ userId: userId ?? null, nonce: `${Date.now()}-${Math.random().toString(36).slice(2)}` }),
    );
  } catch {
  }
}

export function reloadOnLoginElsewhere(): () => void {
  let seen = readLoginMark();
  const check = () => {
    const mark = readLoginMark();
    if (mark === seen) return;
    const loggedIn = loginMarkUserId(mark);
    if (loggedIn !== null && loggedIn === documentUserId) {
      seen = mark;
      return;
    }
    window.location.reload();
  };
  const onStorage = (event: StorageEvent) => {
    if (event.key === LOGIN_MARK_KEY) check();
  };
  const onVisibility = () => {
    if (document.visibilityState === "visible") check();
  };
  window.addEventListener("storage", onStorage);
  document.addEventListener("visibilitychange", onVisibility);
  return () => {
    window.removeEventListener("storage", onStorage);
    document.removeEventListener("visibilitychange", onVisibility);
  };
}

export type WithdrawTarget = { userId: string };

export function withdrawTargetOf(session: Session): WithdrawTarget | null {
  if (!session.loggedIn || !session.withdrawalEnabled || !session.userId) return null;
  return { userId: session.userId };
}

export type WithdrawResult = "completed" | "in_progress";

export async function withdraw(target: WithdrawTarget): Promise<WithdrawResult> {
  if (USE_MOCK) {
    clearSession();
    return "completed";
  }

  const res = await fetch(`${API_BASE}/api/v1/auth/me?userId=${encodeURIComponent(target.userId)}`, {
    method: "DELETE",
    credentials: "include",
    cache: "no-store",
    headers: csrfHeader(),
  });
  if (res.status === 204) {
    forgetLocalSession();
    return "completed";
  }
  if (res.status === 202) return "in_progress";
  const error = await toApiError(res);
  if (error.code === "WITHDRAWAL_IN_PROGRESS") return "in_progress";
  throw error;
}

export function forgetLocalSession(): void {
  clearSession();
  clearGate1Drafts();
  clearReinputDrafts();
  clearInviteCode();
}

export function readCsrfToken(): string | null {
  try {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
  } catch {
    return null;
  }
}

export function csrfHeader(): Record<string, string> {
  const token = readCsrfToken();
  return token ? { "X-XSRF-TOKEN": token } : {};
}
