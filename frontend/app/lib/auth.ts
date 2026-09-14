/**
 * 인증 심(seam). Next.js Server Action + httpOnly 쿠키를 쓰던 자리를
 * 대체한다. 지금은 실제 세션이 없어 localStorage 로 역할만 흉내낸다.
 *
 * Spring Boot 인증(카카오 OAuth + JWT, feat/be/#3 — lib/api.ts 상단 주석 참고)이
 * 붙으면 이 파일 내부만 바꾼다: signIn 은 카카오 인가 코드 교환 엔드포인트를 호출하고,
 * BE 가 httpOnly 세션 쿠키를 직접 심는다 (브라우저 JS 는 토큰을 절대 만지지 않는다 —
 * Spring Boot 가 BFF 역할을 겸한다). getSession 은 `GET /api/auth/me` 로 그 쿠키를
 * 서버에서 검증한 결과만 받는다. 호출부(각 라우트의 clientLoader)는 그대로 둔다.
 */

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "false";
const ROLE_KEY = "itda_role";

export type Role = "org" | "parent" | null;

export async function getSession(): Promise<{ role: Role }> {
  if (USE_MOCK) {
    const role = localStorage.getItem(ROLE_KEY);
    return { role: role === "org" || role === "parent" ? role : null };
  }
  const res = await fetch("/api/auth/me", { credentials: "include" });
  if (!res.ok) return { role: null };
  return res.json();
}

export async function signIn(role: "org" | "parent"): Promise<void> {
  if (USE_MOCK) {
    localStorage.setItem(ROLE_KEY, role);
    return;
  }
  // TODO: 실제 계약(카카오 인가 코드 교환)에 맞춰 재작성 — lib/api.ts 상단 주석 참고
  await fetch("/api/auth/kakao", { method: "POST", credentials: "include" });
}

export async function signOut(): Promise<void> {
  if (USE_MOCK) {
    localStorage.removeItem(ROLE_KEY);
    return;
  }
  await fetch("/api/auth/logout", { method: "POST", credentials: "include" });
}
