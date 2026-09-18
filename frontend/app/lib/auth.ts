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
 *
 * 출입증이 httpOnly 쿠키라 이 파일은 토큰을 읽지도 지우지도 못한다.
 *  - 요청에 붙이는 일: 브라우저가 자동으로 한다 (lib/api.ts 의 credentials 참고)
 *  - 로그아웃: 서버에 부탁해야 한다 (POST /api/v1/auth/logout)
 */

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

const ROLE_KEY = "itda_role";

export type Role = "org" | "parent" | null;

/* ── 역할 ───────────────────────────────────────────── */

function readRole(): Role {
  try {
    const role = localStorage.getItem(ROLE_KEY);
    return role === "org" || role === "parent" ? role : null;
  } catch {
    return null;
  }
}

/**
 * 역할을 로컬에 기록한다. **로그인이 아니다.**
 *
 * 백엔드에 사용자 테이블이 없어 JWT 의 subject 가 kakaoId 뿐이고, 서버가 기관/학부모를
 * 구분할 방법이 없다. 그래서 역할은 아직 클라이언트가 들고 있는다.
 * 학부모 온보딩(routes/parent/consent.tsx)과 로그인 착지 페이지가 부른다.
 *
 * 출입증이 httpOnly 쿠키가 된 지금은 이 값이 **로그인 여부의 표시**도 겸한다 —
 * 쿠키를 JS 가 읽을 수 없어 다른 방법이 없다.
 *
 * TODO: BE 에 `GET /api/v1/auth/me`(역할 포함)가 생기면 이 함수와 readRole() 을
 *       그 응답으로 대체한다.
 */
export function grantRole(role: "org" | "parent"): void {
  try {
    localStorage.setItem(ROLE_KEY, role);
  } catch {
    // 무시 — getSession() 이 null 을 돌려주고 가드가 로그인 화면으로 보낸다.
  }
}

/** 서버가 401 을 주면 쿠키가 만료·무효라는 뜻이다. 로컬 표시도 함께 지운다. */
export function clearSession(): void {
  try {
    localStorage.removeItem(ROLE_KEY);
  } catch {
    // 무시
  }
}

/* ── 세션 ───────────────────────────────────────────── */

export async function getSession(): Promise<{ role: Role }> {
  return { role: readRole() };
}

/** mock 모드인지. 로그인 화면이 "둘러보기" 진입을 띄울지 판단하는 데만 쓴다. */
export function isAuthMock(): boolean {
  return USE_MOCK;
}

/* ── 로그인 · 로그아웃 ──────────────────────────────── */

/**
 * 카카오 로그인 시작. fetch 가 아니라 페이지 이동이다 —
 * 사용자가 카카오 도메인에서 직접 로그인·동의해야 하므로 브라우저가 실제로 가야 한다.
 */
export function startKakaoLogin(): void {
  window.location.href = `${AUTH_ORIGIN}/oauth2/authorization/kakao`;
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
    // 네트워크 실패로 쿠키가 남아도 로컬 역할은 이미 지웠다. 가드가 로그인으로 보낸다.
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
