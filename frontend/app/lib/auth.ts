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
 *
 * 출입증이 httpOnly 쿠키라 이 파일은 토큰을 읽지도 지우지도 못한다.
 *  - 요청에 붙이는 일: 브라우저가 자동으로 한다 (lib/api.ts 의 credentials 참고)
 *  - 로그인 여부·역할: 서버에 묻는다 (GET /api/v1/auth/me)
 *  - 로그아웃: 서버에 부탁해야 한다 (POST /api/v1/auth/logout)
 */

import { ApiError } from "@/lib/apiError";

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
 * 약관 동의와 첫 기관 연결이 아직 안 끝난 상태를 그것과 구분할 방법이 필요하다.
 * (BE 약관 API `/auth/terms` 가 아직 없어 로컬에 둔다.)
 */
const ONBOARDED_KEY = "itda_parent_onboarded";

/**
 * 로그인 의도. 카카오로 떠나기 전에 적어두고 `/oauth/success` 가 읽는다.
 * 역할을 정하지는 않는다 — 처음 가입하는 사람의 회원가입 화면에서 어느 역할을
 * 먼저 골라둘지에만 쓴다. 탭을 닫으면 사라져야 하므로 sessionStorage 를 쓴다.
 */
const INTENT_KEY = "itda_login_intent";

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
};

const ANONYMOUS: Session = { loggedIn: false, signupCompleted: false, role: null };

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
};

function toSession(me: MeResponse): Session {
  return {
    loggedIn: true,
    signupCompleted: me.signupCompleted,
    // signupCompleted 만 보고 분기한다. role 은 가입을 마쳤을 때만 믿는다.
    role: me.signupCompleted ? (me.role ?? null) : null,
    name: me.name,
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
  } catch {
    // 무시
  }
}

/* ── 온보딩 ─────────────────────────────────────────── */

/** 보호자가 약관 동의와 첫 기관 공유 동의까지 마쳤다는 표시. */
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
    return role ? { loggedIn: true, signupCompleted: true, role } : ANONYMOUS;
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

/** 가입을 마친 사람이 처음 볼 화면. 보호자는 약관·첫 연결이 남았으면 온보딩으로. */
export function homePathFor(role: "org" | "parent"): string {
  if (role === "parent") return isOnboarded() ? "/parent" : "/parent/invite";
  return "/dashboard";
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
    return { loggedIn: true, signupCompleted: true, role: input.role };
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

/**
 * 로그아웃. 출입증이 httpOnly 쿠키라 **프론트가 스스로 지울 수 없다.**
 * 서버가 만료된 쿠키를 다시 심어줘야 한다.
 */
export async function signOut(): Promise<void> {
  clearSession();
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
