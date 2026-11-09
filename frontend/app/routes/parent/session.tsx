import { Outlet, redirect, type ShouldRevalidateFunctionArgs } from "react-router";
import { getSession, rememberReturnPath } from "@/lib/auth";

/**
 * 보호자 화면을 옮길 때마다 로그인이 풀렸는지 본다. 보호자 가드(`parent/guard.tsx`) 바로 안쪽에 있다.
 *
 * 가드는 홈 · 리포트로 들어갈 때만 다시 돈다(아이 목록 · 리포트를 다시 부르지 않으려는 선택). 그래서 출입증이 만료된 채
 * 다른 탭으로 옮기면 로그인 화면이 아니라 그 화면 loader 의 401 오류가 뜬다. 세션만 보는 이 얇은 레이아웃이 경로가
 * 바뀔 때마다 돈다(`/auth/me` 한 번). 로그인이 풀렸는지만 보고 나머지는 가드 몫이다.
 */
export async function clientLoader({ request }: { request: Request }) {
  const { loggedIn, withdrawing } = await getSession();
  // 취소된 탐색의 늦은 응답이 마지막으로 선택한 복귀 주소를 덮어쓰지 않게 한다.
  if (request.signal.aborted) return null;
  if (withdrawing) return redirect("/withdrawal");
  if (!loggedIn) {
    rememberReturnPath("parent", new URL(request.url));
    return redirect("/parent/invite");
  }
  return null;
}

export function shouldRevalidate({ currentUrl, nextUrl, defaultShouldRevalidate }: ShouldRevalidateFunctionArgs) {
  return currentUrl.pathname !== nextUrl.pathname || defaultShouldRevalidate;
}

export default function ParentSessionRoute() {
  return <Outlet />;
}
