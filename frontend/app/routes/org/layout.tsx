import { Outlet, redirect, useLoaderData } from "react-router";
import { skipSearchOnlyChange } from "@/lib/revalidate";
import { loadOrgLive, OrgShell } from "@/components/org/Shell";
import { getSession, isAuthMock, rememberReturnPath } from "@/lib/auth";
import { MY_INSTITUTION } from "@/lib/mock/data";
import "./org.css";

/**
 * proxy.ts 의 ORG_PREFIXES 게이트를 대체한다.
 * 이 clientLoader 가 끝나기 전까지는 root.tsx 의 HydrateFallback 이 보이고,
 * 화면(children)은 절대 먼저 그려지지 않는다.
 *
 * 메뉴 배지 숫자도 여기서 처음 센다 — 화면이 useRevalidator() 로 갱신을 요청하면
 * 이 clientLoader 도 다시 돌아 배지가 바로 줄어든다. 그 사이에는 Shell 이 10초마다 다시 센다.
 */
/** 칸 바꾸기 · `?open=` 지우기로는 다시 불러오지 않는다 (lib/revalidate) */
export const shouldRevalidate = skipSearchOnlyChange;

export async function clientLoader({ request }: { request: Request }) {
  const { loggedIn, signupCompleted, role, institutionName, termsAgreed } = await getSession();
  // 카카오 로그인만 하고 역할을 안 고른 사람 — 보호 API 가 403 SIGNUP_NOT_COMPLETED 를 준다.
  if (loggedIn && !signupCompleted) return redirect("/signup?role=org");
  // 로그인이 풀렸으면 다시 로그인한 뒤 이 화면으로 돌아오게 주소를 남긴다. 다른 역할이 들어온 경우는 남기지 않는다
  if (!loggedIn) {
    rememberReturnPath("org", new URL(request.url));
    return redirect("/login");
  }
  if (role !== "org") return redirect("/login");
  // 기관 약관 전에는 보호 API 가 모두 403 TERMS_NOT_AGREED 다 — 배지를 묻기 전에 약관 화면으로 보낸다.
  if (!termsAgreed) return redirect("/org-terms");

  // 메뉴 배지. 백엔드에 아직 없는 기능은 null → 배지를 띄우지 않는다 (loadIfReady 참고)
  const live = await loadOrgLive();

  // 머리띠의 내 정보. 사람 이름은 저장하지 않으므로 /auth/me 의 기관명(#236)만 쓴다.
  // 서버가 기관명을 안 주면 비워 둔다 — mock 기관명을 띄우면 로그인한 사람과 다른 기관이 보인다.
  const me = { institutionName: isAuthMock() ? MY_INSTITUTION.name : (institutionName ?? null) };

  return {
    me,
    live,
  };
}

export default function OrgLayoutRoute() {
  const { me, live } = useLoaderData<typeof clientLoader>();
  return (
    <OrgShell me={me} live={live}>
      <Outlet />
    </OrgShell>
  );
}
