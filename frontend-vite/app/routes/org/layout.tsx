import { Outlet, redirect, useLoaderData } from "react-router";
import { OrgShell } from "@/components/org/Shell";
import { getSession } from "@/lib/auth";
import {
  getBlockedQueue,
  getGate1Queue,
  getInbox,
  getInsights,
  getMatchingQueue,
} from "@/lib/api";
import { MY_INSTITUTION } from "@/lib/mock/data";

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
  const { role } = await getSession();
  if (role !== "org") return redirect("/login");

  const [matching, blocked, gate1, insights, inbox] = await Promise.all([
    getMatchingQueue(),
    getBlockedQueue(),
    getGate1Queue(),
    getInsights(),
    getInbox(),
  ]);

  return {
    counts: {
      matching: matching.length,
      reinput: blocked.length,
      gate1: gate1.filter((s) => s.gate1Status === "pending").length,
      gate2: insights.filter(
        (i) => i.primarySource.id === MY_INSTITUTION.id && i.gate2Status === "pending",
      ).length,
      inbox: inbox.filter((i) => !i.read).length,
    },
  };
}

export default function OrgLayoutRoute() {
  const { counts } = useLoaderData<typeof clientLoader>();
  return (
    <OrgShell counts={counts}>
      <Outlet />
    </OrgShell>
  );
}
