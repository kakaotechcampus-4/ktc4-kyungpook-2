/**
 * 인증 심(seam). Next.js Server Action + httpOnly 쿠키를 쓰던 자리를 대체한다.
 *
 * ⚠️ 전송 방식이 쿠키가 아니라 토큰이다.
 *    BE(`POST /api/v1/auth/kakao`)는 accessToken 을 응답 **본문**으로 준다. 즉 BE 가
 *    쿠키를 심어주는 BFF 구조가 아니라, 브라우저 JS 가 토큰을 직접 보관했다가 매 요청에
 *    `Authorization: Bearer <token>` 으로 붙여야 한다. 그 보관소가 이 파일이고,
 *    실제로 헤더를 붙이는 곳은 lib/api.ts 의 request() 다.
 *
 * 흐름: 로그인 화면 → 카카오 인가 화면 → `/oauth/kakao/callback`
 *       → state 검증(lib/oauth-state.ts) → exchangeKakaoCode() → 토큰 저장
 */

import { issueState } from "@/lib/oauth-state";

/**
 * 인증만 실연동하고 나머지 데이터는 mock 으로 둘 수 있게 플래그를 분리했다
 * (데이터 쪽은 lib/api.ts 의 VITE_USE_MOCK).
 *
 * 하나로 묶여 있으면 로그인을 켜는 순간 대시보드·아이 목록·게이트가 전부 빈 화면이
 * 된다 — BE 에 열려 있는 것은 인증과 원본 기록뿐이기 때문이다.
 */
const USE_MOCK = import.meta.env.VITE_AUTH_MOCK !== "false";
const BASE = import.meta.env.VITE_API_BASE_URL ?? "";

const ROLE_KEY = "itda_role";
const TOKEN_KEY = "itda_token";

const KAKAO_AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";
const KAKAO_CLIENT_ID = import.meta.env.VITE_KAKAO_CLIENT_ID ?? "";
const KAKAO_REDIRECT_URI = import.meta.env.VITE_KAKAO_REDIRECT_URI ?? "";

export type Role = "org" | "parent" | null;

/* ── 토큰 ───────────────────────────────────────────── */

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
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // 저장이 막히면 새로고침 시 로그인이 풀린다. 로그인 자체를 막지는 않는다.
  }
}

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
 * BE 에 사용자 테이블이 없어 JWT 의 subject 가 kakaoId 뿐이고, 서버가 기관/학부모를
 * 구분할 방법이 없다. 그래서 역할은 아직 클라이언트가 들고 있는다.
 * 학부모 온보딩(routes/parent/consent.tsx)과 카카오 콜백이 부른다.
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

/* ── 세션 ───────────────────────────────────────────── */

export async function getSession(): Promise<{ role: Role }> {
  const role = readRole();
  if (USE_MOCK) return { role };

  // 실연동 모드에서는 토큰이 있어야 로그인으로 친다. 역할은 위 TODO 참고.
  if (!getToken()) return { role: null };
  return { role };
}

export async function signOut(): Promise<void> {
  // JWT 는 서버가 세션을 들고 있지 않다. 토큰을 버리는 것이 곧 로그아웃이다.
  try {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ROLE_KEY);
  } catch {
    // 무시
  }
}

/* ── 카카오 ─────────────────────────────────────────── */

/** .env 에 카카오 키가 채워져 있는지. 로그인 화면이 안내를 띄우는 데 쓴다. */
export function isKakaoConfigured(): boolean {
  return Boolean(KAKAO_CLIENT_ID && KAKAO_REDIRECT_URI);
}

/**
 * 카카오 인가 화면 주소를 만든다. 부르는 순간 state 가 새로 발급·저장된다
 * (lib/oauth-state.ts) — 그래서 만들어 두고 나중에 쓰면 안 되고, 이동 직전에 부른다.
 *
 * redirect_uri 는 카카오 개발자 콘솔 · BE 의 `kakao.redirect-uri` · 여기 셋이
 * **글자까지 같아야** 한다. 하나라도 다르면 카카오가 KOE006 으로 거절한다.
 */
export function buildKakaoAuthorizeUrl(): string {
  const params = new URLSearchParams({
    client_id: KAKAO_CLIENT_ID,
    redirect_uri: KAKAO_REDIRECT_URI,
    response_type: "code",
    state: issueState(),
  });
  return `${KAKAO_AUTHORIZE_URL}?${params.toString()}`;
}

export type KakaoLoginResult = {
  kakaoId: number;
  nickname: string | null;
};

/**
 * 인가 코드를 JWT 로 교환한다.
 * **반드시 state 검증을 통과한 뒤에만** 부른다 — 검증 전에 코드를 서버로 보내면
 * state 를 쓰는 의미가 없어진다. (호출부: routes/oauth-kakao-callback.tsx)
 *
 * BE 계약:
 *   POST /api/v1/auth/kakao?code=...
 *   성공 { result, data: { accessToken, kakaoId, nickname }, message }
 *   실패 { result, code, message }
 *
 * BE 는 아직 state 를 받지 않는다. 받게 되면 여기서 함께 보낸다.
 */
export async function exchangeKakaoCode(code: string): Promise<KakaoLoginResult> {
  const res = await fetch(`${BASE}/api/v1/auth/kakao?code=${encodeURIComponent(code)}`, {
    method: "POST",
    cache: "no-store",
  });

  const body = await res.json().catch(() => null);

  if (!res.ok) {
    throw new Error(body?.message ?? "카카오 로그인에 실패했습니다.");
  }

  const data = body?.data;
  if (!data?.accessToken) {
    // 200 인데 본문 모양이 다른 경우 — 계약이 바뀌었는지 확인해야 한다.
    throw new Error("로그인 응답에 accessToken 이 없습니다.");
  }

  setToken(data.accessToken);
  return { kakaoId: data.kakaoId, nickname: data.nickname ?? null };
}
