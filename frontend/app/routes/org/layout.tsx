import { Outlet, redirect, useLoaderData } from "react-router";
import { OrgShell } from "@/components/org/Shell";
import { getSession, isAuthMock } from "@/lib/auth";
import {
  getBlockedQueue,
  getGate1Queue,
  getInbox,
  getInsights,
  getMatchingQueue,
  loadIfReady,
} from "@/lib/api";
import { ME, MY_INSTITUTION } from "@/lib/mock/data";

/**
 * proxy.ts 의 ORG_PREFIXES 게이트를 대체한다.
 * 이 clientLoader 가 끝나기 전까지는 root.tsx 의 HydrateFallback 이 보이고,
 * 화면(children)은 절대 먼저 그려지지 않는다.
 *
 * 사이드바 배지 카운트도 여기서 계산한다 — 큐를 처리하는 라우트가
 * useRevalidator() 로 갱신을 요청하면 이 clientLoader 도 다시 실행되어
 * 배지가 실시간으로 줄어든다.
 */
export async function clientLoader() {
  const { loggedIn, signupCompleted, role, name, institutionName } = await getSession();
  // 카카오 로그인만 하고 역할을 안 고른 사람 — 보호 API 가 403 SIGNUP_NOT_COMPLETED 를 준다.
  if (loggedIn && !signupCompleted) return redirect("/signup?role=org");
  if (role !== "org") return redirect("/login");

  // 백엔드에 아직 없는 기능은 null → 배지를 띄우지 않는다 (loadIfReady 참고)
  const [matching, blocked, gate1, insights, inbox] = await Promise.all([
    loadIfReady("matchingQueue", getMatchingQueue),
    loadIfReady("blockedQueue", getBlockedQueue),
    loadIfReady("gate1", getGate1Queue),
    loadIfReady("insights", getInsights),
    loadIfReady("inbox", getInbox),
  ]);

  // 사이드바 하단의 내 정보. 실연동에서는 /auth/me 의 이름 · 기관명(#236)을 쓴다.
  // 서버가 기관명을 안 주면 비워 둔다 — mock 기관명을 띄우면 로그인한 사람과 다른 기관이 보인다.
  const me = isAuthMock()
    ? { name: ME.name, institutionName: MY_INSTITUTION.name }
    : { name: name ?? null, institutionName: institutionName ?? null };

  return {
    me,
    counts: {
      matching: matching?.length ?? null,
      reinput: blocked?.length ?? null,
      gate1: gate1?.filter((s) => s.gate1Status === "pending").length ?? null,
      // getInsights 기본값이 pending 이고, BE 가 우리 기관이 승인 주체인 것만 준다
      gate2: insights?.length ?? null,
      inbox: inbox?.filter((i) => !i.read).length ?? null,
    },
  };
}

export default function OrgLayoutRoute() {
  const { me, counts } = useLoaderData<typeof clientLoader>();
  return (
    <OrgShell me={me} counts={counts}>
      <Outlet />
    </OrgShell>
  );
}
