import { Outlet, redirect, type ShouldRevalidateFunctionArgs } from "react-router";
import { getSession, rememberReturnPath } from "@/lib/auth";

/**
 * 기관 화면을 옮길 때마다 로그인이 풀렸는지 본다. 기관 가드(`org/layout.tsx`) 바로 안쪽에 있다.
 *
 * 같은 레이아웃 안에서 옮길 때(`/gate1 → /children`) React Router 는 가드의 loader 를 다시 부르지 않는다.
 * 그래서 출입증이 만료된 채 메뉴를 누르면 로그인 화면이 아니라 그 화면 loader 의 401 오류가 뜬다. 가드를 매번
 * 다시 돌리면 배지용 목록까지 다시 부르므로, 세션만 보는 이 얇은 레이아웃이 경로가 바뀔 때마다 돈다(`/auth/me` 한 번).
 * 역할 · 가입 · 약관 판단은 가드 몫이다 — 여기서는 로그인이 풀렸는지만 본다.
 */
export async function clientLoader({ request }: { request: Request }) {
  const { loggedIn } = await getSession();
  // 취소된 탐색의 늦은 응답이 마지막으로 선택한 복귀 주소를 덮어쓰지 않게 한다.
  if (request.signal.aborted) return null;
  if (!loggedIn) {
    rememberReturnPath("org", new URL(request.url));
    return redirect("/login");
  }
  return null;
}

export function shouldRevalidate({ currentUrl, nextUrl, defaultShouldRevalidate }: ShouldRevalidateFunctionArgs) {
  return currentUrl.pathname !== nextUrl.pathname || defaultShouldRevalidate;
}

export default function OrgSessionRoute() {
  return <Outlet />;
}
