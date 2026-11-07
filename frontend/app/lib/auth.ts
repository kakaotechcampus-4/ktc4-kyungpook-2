/**
 * 인증 심(seam).
 *
 * 로그인은 **백엔드가 전부 맡는다**. Spring Security oauth2Login 이 인가 요청(state 포함),
 * 카카오 토큰 교환, 사용자 조회까지 처리하고, 끝나면 출입증을 httpOnly 쿠키로 심은 뒤
 * 프론트로 돌려보낸다. 프론트가 인가 코드나 토큰을 직접 만지는 부분은 없다.
 *
 *   /login → (BE) /oauth2/authorization/kakao → 카카오
 *          → (BE) /login/oauth2/code/kakao → 쿠키 발급
 *          → /oauth/success   (실패 시 /login)
 *          → GET /api/v1/auth/me
 *              signupCompleted: true  → 역할별 첫 화면
 *              signupCompleted: false → /signup → POST /api/v1/auth/signup
 *              보호자 termsAgreed: false → /parent/invite 약관 단계 → POST /api/v1/auth/terms
 *
 * 출입증이 httpOnly 쿠키라 이 파일은 토큰을 읽지도 지우지도 못한다.
 *  - 요청에 붙이는 일: 브라우저가 자동으로 한다 (lib/api.ts 의 credentials 참고)
 *  - 로그인 여부·역할: 서버에 묻는다 (GET /api/v1/auth/me)
 *  - 로그아웃: 서버에 부탁해야 한다 (POST /api/v1/auth/logout)
 */

import { ApiError } from "@/lib/apiError";
import { clearGate1Drafts } from "@/lib/gate1Draft";
import { clearReinputDrafts } from "@/lib/reinputDraft";
import { MOCK_TERMS } from "@/lib/mock/terms";

/**
 * 인증만 실연동하고 나머지 데이터는 mock 으로 둘 수 있게 플래그를 분리했다
 * (데이터 쪽은 lib/api.ts 의 VITE_USE_MOCK).
 */
const USE_MOCK = import.meta.env.VITE_AUTH_MOCK !== "false";

/**
 * 로그인 진입 주소의 오리진.
 *
 * 운영은 비워 둔다 — nginx 가 같은 오리진의 `/oauth2/` 를 백엔드로 넘긴다.
 * dev 는 `http://localhost:8080` 이 필요하다. Spring 이 **자기가 받은 주소**로
 * redirect_uri 를 조립하는데, Vite 프록시를 거치면 그 값이 카카오 콘솔 등록값과
 * 어긋나 KOE006 이 나기 때문이다. (BE 의 application-secret.yml.example 참고)
 */
const AUTH_ORIGIN = import.meta.env.VITE_AUTH_ORIGIN ?? "";
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "";

/** mock 모드에서만 쓴다. 실연동에서는 역할을 서버(`/auth/me`)가 알려준다. */
const ROLE_KEY = "itda_role";

/**
 * 온보딩을 끝냈다는 표시. 역할과 따로 둔다 — 보호자는 가입 직후에 역할이 정해지는데,
 * 첫 기관 연결이 아직 안 끝난 상태를 그것과 구분할 방법이 필요하다.
 * 약관 동의는 여기 두지 않는다 — 서버가 저장하고 `/auth/me` 의 termsAgreed 로 알려준다.
 */
const ONBOARDED_KEY = "itda_parent_onboarded";

/** mock 모드에서만 쓴다. 보호자가 약관에 동의했다는 표시. 실연동에서는 서버(`termsAgreed`)가 기준이다. */
const TERMS_KEY = "itda_parent_terms";

/**
 * 로그인 의도. 카카오로 떠나기 전에 적어두고 `/oauth/success` 가 읽는다.
 * 역할을 정하지는 않는다 — 처음 가입하는 사람의 회원가입 화면에서 어느 역할을
 * 먼저 골라둘지에만 쓴다. 탭을 닫으면 사라져야 하므로 sessionStorage 를 쓴다.
 */
const INTENT_KEY = "itda_login_intent";

/**
 * 초대 링크(`/parent/invite?code=`)로 받은 코드. 카카오 로그인과 회원가입을 다녀오는 동안
 * 들고 있다가, 보호자 화면에 들어오면 수락(G-04)에 쓴다. 로그인 의도와 같은 이유로
 * sessionStorage 다. 카톡 인앱 브라우저에서 외부 브라우저로 넘어가면 사라질 수 있어
 * P-01 에 직접 입력 칸을 따로 둔다.
 */
const INVITE_CODE_KEY = "itda_invite_code";

/**
 * 로그인이 풀려 로그인 화면으로 보낼 때 가려던 주소와 그 화면의 역할(`{ role, path }`).
 * 다시 로그인하면 `/oauth/success` 가 한 번 읽고 지운다. 로그인 의도와 같은 이유로 sessionStorage 다 —
 * 카카오를 다녀오는 동안은 같은 탭이라 남고, 탭을 닫으면 사라진다.
 */
const RETURN_PATH_KEY = "itda_return_path";

export type Role = "org" | "parent" | null;

/**
 * 라우트 가드가 보는 세션.
 *  - loggedIn=false            : 비로그인 → 로그인 화면
 *  - loggedIn, !signupCompleted: 카카오 로그인만 하고 역할을 안 고름 → /signup
 *  - signupCompleted           : role 이 정해져 있다
 */
export type Session = {
  loggedIn: boolean;
  signupCompleted: boolean;
  role: Role;
  /** 카카오 닉네임. 동의를 거부하면 없다. */
  name?: string;
  /** 가입 때 입력한 기관명. 기관 계정만 있고, 서버가 기관을 못 찾으면 없다. */
  institutionName?: string;
  /**
   * 보호자가 서비스 약관(지금 판)에 동의했는지. false 면 서버가 보호 API 를 403 TERMS_NOT_AGREED 로 막는다.
   * 기관 계정은 약관이 없어 늘 true, 가입 미완료는 false.
   */
  termsAgreed: boolean;
};

const ANONYMOUS: Session = { loggedIn: false, signupCompleted: false, role: null, termsAgreed: false };

/**
 * GET /auth/me · POST /auth/signup 의 data.
 * 값이 없는 필드는 null 이 아니라 **아예 빠진다**(BE CurrentUserResponse 의 NON_NULL).
 */
type MeResponse = {
  signupCompleted: boolean;
  userId: string;
  name?: string;
  role?: "org" | "parent";
  institutionId?: string;
  /** role === "org" 일 때만 (#236) */
  institutionName?: string;
  /** 늘 온다. 기관은 true, 가입 미완료는 false */
  termsAgreed: boolean;
};

function toSession(me: MeResponse): Session {
  return {
    loggedIn: true,
    signupCompleted: me.signupCompleted,
    // signupCompleted 만 보고 분기한다. role 은 가입을 마쳤을 때만 믿는다.
    role: me.signupCompleted ? (me.role ?? null) : null,
    name: me.name,
    institutionName: me.institutionName,
    termsAgreed: me.signupCompleted && me.termsAgreed === true,
  };
}

/** 실패 응답 { result, code, message } 를 ApiError 로. */
async function toApiError(res: Response): Promise<ApiError> {
  const body = await res.json().catch(() => ({}));
  return new ApiError(res.status, body.code ?? "UNKNOWN", body.message);
}

/* ── 역할 (mock 전용) ───────────────────────────────── */

function readRole(): Role {
  try {
    const role = localStorage.getItem(ROLE_KEY);
    return role === "org" || role === "parent" ? role : null;
  } catch {
    return null;
  }
}

/**
 * mock 모드에서 역할을 로컬에 기록한다. **로그인이 아니다.**
 * "mock 데이터로 둘러보기" 버튼이 부른다. 실연동에서는 아무것도 하지 않는다 —
 * 역할은 회원가입(`signup()`)으로 서버에 정해지고 `/auth/me` 가 알려준다.
 */
export function grantRole(role: "org" | "parent"): void {
  if (!USE_MOCK) return;
  try {
    localStorage.setItem(ROLE_KEY, role);
  } catch {
    // 무시 — getSession() 이 비로그인을 돌려주고 가드가 로그인 화면으로 보낸다.
  }
}

/** 서버가 401 을 주면 쿠키가 만료·무효라는 뜻이다. 로컬 표시도 함께 지운다. */
export function clearSession(): void {
  try {
    localStorage.removeItem(ROLE_KEY);
    localStorage.removeItem(ONBOARDED_KEY);
    localStorage.removeItem(TERMS_KEY);
  } catch {
    // 무시
  }
}

/* ── 온보딩 ─────────────────────────────────────────── */

/** 보호자가 첫 기관 공유 동의까지 마쳤다는 표시. 약관 동의는 따로 본다(Session.termsAgreed). */
export function markOnboarded(): void {
  try {
    localStorage.setItem(ONBOARDED_KEY, "1");
  } catch {
    // 무시 — 온보딩 화면을 한 번 더 보게 될 뿐이다.
  }
}

export function isOnboarded(): boolean {
  try {
    return localStorage.getItem(ONBOARDED_KEY) === "1";
  } catch {
    return false;
  }
}

/* ── 세션 ───────────────────────────────────────────── */

/**
 * GET /api/v1/auth/me — 라우트 가드가 화면에 들어갈 때마다 부른다.
 *
 * 401 은 "다시 로그인" 이라 에러가 아니라 비로그인 세션으로 돌려준다.
 * 그 밖의 실패(5xx·네트워크)는 그대로 던진다 — 비로그인으로 바꿔 삼키면
 * 서버 장애가 "로그아웃됨" 으로 보인다.
 *
 * 이 GET 이 BE 의 XSRF-TOKEN 쿠키를 처음 심어주는 요청이기도 하다
 * (BE CsrfCookieFilter). 그래서 signup() 같은 쓰기 요청 전에 한 번은 불려 있어야 한다.
 */
export async function getSession(): Promise<Session> {
  if (USE_MOCK) {
    const role = readRole();
    return role
      ? { loggedIn: true, signupCompleted: true, role, termsAgreed: role === "org" || readTermsMark() }
      : ANONYMOUS;
  }

  const res = await fetch(`${API_BASE}/api/v1/auth/me`, {
    credentials: "include",
    cache: "no-store",
  });
  if (res.status === 401) {
    clearSession();
    return ANONYMOUS;
  }
  if (!res.ok) throw await toApiError(res);
  const body = await res.json();
  return toSession(body.data as MeResponse);
}

/**
 * 가입을 마친 사람이 처음 볼 화면. 보호자는 약관 · 첫 연결이 남았거나, 초대 링크로 받아 둔
 * 코드가 있으면 온보딩(약관 또는 코드 수락)으로. 기관은 약관이 남았으면 기관 약관 화면으로.
 */
export function homePathFor(role: "org" | "parent", termsAgreed: boolean): string {
  if (role === "parent") {
    return termsAgreed && isOnboarded() && !readInviteCode() ? "/parent" : "/parent/invite";
  }
  return termsAgreed ? "/dashboard" : "/org-terms";
}

/* ── 약관 동의 ──────────────────────────────────────── */

/** BE TermKey 값 그대로. 보호자는 앞의 둘, 기관은 org_ 셋이 모두 필수다(다른 역할의 약관은 보낼 수 없다). */
export type TermKey = "tos" | "privacy" | "org_tos" | "org_privacy" | "org_child_data";

/** 약관 한 개의 지금 판 (GET /api/v1/terms 의 원소). */
export interface TermsDocument {
  key: TermKey;
  title: string;
  /** 동의할 때 `versions` 에 그대로 보낸다 */
  version: string;
  /** 시행일 YYYY-MM-DD */
  effectiveDate: string;
  /** 팀 검토 전 초안 — 화면은 본문 위에 초안 표시를 띄운다 */
  draft: boolean;
  /** 줄바꿈만 살리는 그냥 글 */
  body: string;
}

/**
 * GET /api/v1/terms?role= — 그 역할이 동의해야 하는 약관의 지금 판 본문. 로그인 없이 부른다.
 *
 * 동의 요청과 같은 인증 mock 스위치를 따른다 — 둘이 다르면 mock 판으로 실서버에 동의해 409 가 난다.
 */
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

/**
 * POST /api/v1/auth/terms — 약관 동의(보호자 · 기관). 체크한 약관마다 화면이 받아 둔 판(사용자가 읽은 판)을 붙여 보낸다.
 * 서버는 그 판으로 기록하고, 지금 판과 다르면 아무것도 기록하지 않고 409 TERMS_VERSION_CHANGED 를 준다.
 *
 * 인증 mock 스위치(`VITE_AUTH_MOCK`)를 따른다. 데이터 mock(`VITE_USE_MOCK`)을 따르면 "로그인만 실연동"
 * 모드에서 저장한 척만 하고 다음 `/auth/me` 는 계속 termsAgreed: false 를 돌려준다.
 *
 * 실패는 ApiError 로 던진다 (INVALID_REQUEST · SIGNUP_NOT_COMPLETED · TERMS_VERSION_CHANGED · 401).
 */
export async function agreeTerms(agreed: { key: TermKey; version: string }[]): Promise<void> {
  if (USE_MOCK) {
    try {
      localStorage.setItem(TERMS_KEY, "1");
    } catch {
      // 무시 — 약관 화면을 한 번 더 보게 될 뿐이다.
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

/* ── 초대 코드 ──────────────────────────────────────── */

export function saveInviteCode(code: string): void {
  try {
    sessionStorage.setItem(INVITE_CODE_KEY, code);
  } catch {
    // 무시 — 로그인 뒤 코드를 직접 입력하면 된다.
  }
}

export function readInviteCode(): string | null {
  try {
    return sessionStorage.getItem(INVITE_CODE_KEY);
  } catch {
    return null;
  }
}

/** 수락이 끝났거나(성공 · 이미 연결됨) 코드가 틀려 더 쓸 수 없을 때 지운다. */
export function clearInviteCode(): void {
  try {
    sessionStorage.removeItem(INVITE_CODE_KEY);
  } catch {
    // 무시
  }
}

/** mock 모드인지. 로그인 화면이 "둘러보기" 진입을 띄울지 판단하는 데만 쓴다. */
export function isAuthMock(): boolean {
  return USE_MOCK;
}

/* ── 회원가입 ───────────────────────────────────────── */

/** BE enum 값 그대로. 화면 표시 이름은 회원가입 화면이 붙인다. */
export type OrganizationType = "SCHOOL" | "CENTER" | "ACTIVITY_SUPPORT";

export type SignupInput =
  | { role: "parent" }
  | {
      role: "org";
      organizationName: string;
      organizationType: OrganizationType;
      /** 하이픈 없이 숫자 10자리 */
      businessNumber: string;
    };

/**
 * POST /api/v1/auth/signup → 201. 응답은 /auth/me 의 가입 완료 응답과 같다.
 *
 * 보호자는 role **만** 보낸다. 기관 필드가 `""` 로라도 섞이면 BE 가 400 을 준다.
 * 그래서 폼 상태를 그대로 싣지 않고 역할별로 본문을 새로 만든다.
 *
 * 실패는 ApiError 로 던진다. 화면이 code 로 나눠 처리한다
 * (ALREADY_SIGNED_UP · DUPLICATE_BUSINESS_NUMBER · INVALID_REQUEST).
 */
export async function signup(input: SignupInput): Promise<Session> {
  if (USE_MOCK) {
    grantRole(input.role);
    return {
      loggedIn: true,
      signupCompleted: true,
      role: input.role,
      termsAgreed: input.role === "org" || readTermsMark(),
    };
  }

  const body =
    input.role === "parent"
      ? { role: "parent" }
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

/* ── 로그인 · 로그아웃 ──────────────────────────────── */

/**
 * 카카오 로그인 시작. fetch 가 아니라 페이지 이동이다 —
 * 사용자가 카카오 도메인에서 직접 로그인·동의해야 하므로 브라우저가 실제로 가야 한다.
 */
export function startKakaoLogin(intent: "org" | "parent"): void {
  try {
    sessionStorage.setItem(INTENT_KEY, intent);
  } catch {
    // 무시 — 회원가입 화면에서 역할을 미리 골라두지 못할 뿐이다.
  }
  window.location.href = `${AUTH_ORIGIN}/oauth2/authorization/kakao`;
}

/** 로그인 의도를 **한 번만** 읽는다 — 읽고 지운다. 다음 로그인까지 남으면 안 된다. */
export function takeLoginIntent(): "org" | "parent" | null {
  try {
    const intent = sessionStorage.getItem(INTENT_KEY);
    sessionStorage.removeItem(INTENT_KEY);
    return intent === "org" || intent === "parent" ? intent : null;
  } catch {
    return null;
  }
}

/* ── 로그인 뒤 돌아갈 주소 ──────────────────────────── */

/**
 * 로그인이 풀려 로그인 화면으로 보내기 직전에 가려던 주소(경로 + `?` 뒤)를 남긴다. 가드 · 세션 확인 레이아웃 ·
 * 최상위 오류 화면이 부른다. 역할도 함께 남겨, 같은 탭에서 다른 역할로 로그인하면 쓰지 않는다.
 */
export function rememberReturnPath(role: "org" | "parent", url: URL): void {
  try {
    sessionStorage.setItem(RETURN_PATH_KEY, JSON.stringify({ role, path: url.pathname + url.search }));
  } catch {
    // 무시 — 로그인 뒤 첫 화면으로 갈 뿐이다.
  }
}

/** 로그인이 풀려서 왔는지. 로그인 화면의 만료 안내에만 쓴다 — 지우지 않는다. */
export function hasReturnPath(): boolean {
  try {
    return sessionStorage.getItem(RETURN_PATH_KEY) !== null;
  } catch {
    return false;
  }
}

/**
 * 남긴 주소를 **한 번만** 읽는다 — 읽고 지운다. 역할이 다르면 버린다(남의 화면 주소로 보내면 403 이다).
 * 우리 앱 안의 경로(`/` 로 시작, `//` 는 아님)만 돌려준다. 역할이 없으면(가입 전) 지우기만 한다.
 */
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

/**
 * 로그아웃. 출입증이 httpOnly 쿠키라 **프론트가 스스로 지울 수 없다.**
 * 서버가 만료된 쿠키를 다시 심어줘야 한다.
 */
export async function signOut(): Promise<void> {
  clearSession();
  // 요약 검토 · 검증 수정 요청에서 로그인이 풀려 맡겨 둔 글 — 아이에 대한 글이라 남기지 않는다
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
    // 네트워크 실패로 쿠키가 남아도 로컬 표시는 이미 지웠다. 가드가 /auth/me 로 다시 확인한다.
  }
}

/* ── CSRF ───────────────────────────────────────────── */

/**
 * 쿠키가 자동 전송되면서 CSRF 방어가 필요해졌다. 백엔드가 `XSRF-TOKEN` 쿠키를
 * 내려주고(이 쿠키만은 httpOnly 가 아니다), 프론트가 `X-XSRF-TOKEN` 헤더로 되돌려준다.
 * 없으면 쓰기 요청이 전부 403 이 된다.
 */
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
