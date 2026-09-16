/**
 * 인증 심(seam). Next.js Server Action + httpOnly 쿠키를 쓰던 자리를
 * 대체한다. 지금은 실제 세션이 없어 localStorage 로 역할만 흉내낸다.
 *
 * ⚠️ 전송 방식이 쿠키가 아니라 토큰이다.
 *    Spring Boot 인증(카카오 OAuth + JWT, feat/be/#3 — lib/api.ts 상단 주석 참고)은
 *    `POST /api/auth/kakao` 응답 **본문**으로 accessToken 을 준다. 즉 BE 가 쿠키를
 *    심어주는 BFF 구조가 아니라, 브라우저 JS 가 토큰을 직접 보관했다가 매 요청에
 *    `Authorization: Bearer <token>` 으로 붙여야 한다. 그 보관소가 이 파일이고,
 *    실제로 헤더를 붙이는 곳은 lib/api.ts 의 request() 다.
 *
 * 호출부(각 라우트의 clientLoader)는 그대로 둔다 — 바뀌는 건 이 파일 내부뿐이다.
 */

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "false";
const ROLE_KEY = "itda_role";
const TOKEN_KEY = "itda_token";

export type Role = "org" | "parent" | null;

/**
 * 저장된 JWT 를 돌려준다. lib/api.ts 의 request() 가 매 호출마다 부른다.
 *
 * localStorage 는 XSS 에 노출되면 토큰째 털린다. 원래대로라면 httpOnly 쿠키가
 * 안전하지만, BE 계약이 토큰을 본문으로 주는 형태라 브라우저가 직접 보관할
 * 수밖에 없다. BE 가 쿠키 방식으로 바꾸면 이 파일만 되돌리면 된다.
 */
export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export async function getSession(): Promise<{ role: Role }> {
  if (USE_MOCK) {
    const role = localStorage.getItem(ROLE_KEY);
    return { role: role === "org" || role === "parent" ? role : null };
  }
  const token = getToken();
  if (!token) return { role: null };

  const res = await fetch("/api/auth/me", {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    // 만료·위조 토큰을 들고 계속 401 을 맞지 않도록 즉시 버린다.
    clearToken();
    return { role: null };
  }
  return res.json();
}

export async function signIn(role: "org" | "parent"): Promise<void> {
  if (USE_MOCK) {
    localStorage.setItem(ROLE_KEY, role);
    return;
  }
  // TODO: 실제 계약(카카오 인가 코드 교환)에 맞춰 재작성 — lib/api.ts 상단 주석 참고.
  //       `POST /api/auth/kakao?code=...` → { accessToken, kakaoId, nickname }
  //       현재 시그니처(role 을 받는다)는 mock 용이라 로그인 화면과 함께 갈아엎어야 한다.
  const res = await fetch("/api/auth/kakao", { method: "POST" });
  if (!res.ok) throw new Error("로그인에 실패했습니다.");
  const { accessToken } = await res.json();
  setToken(accessToken);
}

export async function signOut(): Promise<void> {
  if (USE_MOCK) {
    localStorage.removeItem(ROLE_KEY);
    return;
  }
  // JWT 는 서버가 세션을 들고 있지 않다. 토큰을 버리는 것이 곧 로그아웃이다.
  clearToken();
}
