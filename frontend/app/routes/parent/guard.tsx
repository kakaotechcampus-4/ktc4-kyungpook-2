import { Outlet, redirect, useLoaderData, type ShouldRevalidateFunctionArgs } from "react-router";
import { ChildProvider } from "@/components/parent/ChildContext";
import { ParentNotReady } from "@/components/parent/NotReady";
import { getSession, isOnboarded } from "@/lib/auth";
import { getGuardianInsights, getParentChildren, isReady, loadIfReady } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

/**
 * proxy.ts 의 PARENT_PREFIX 게이트를 대체한다. /parent/invite, /parent/consent 는
 * 이 guard 밖(parent/shell.tsx 아래 직속)에 있어 로그인 없이 접근 가능하다.
 *
 * 여기서 아이 목록도 함께 읽어서 ChildProvider 로 내려준다 — 아이 전환은
 * localStorage 에 쓰고 revalidate() 하는 방식이라, 이 clientLoader 가
 * 그 값을 매번 다시 읽어오는 지점이 된다.
 */
export async function clientLoader() {
  const { loggedIn, signupCompleted, role } = await getSession();
  if (loggedIn && !signupCompleted) return redirect("/signup?role=parent");
  if (role !== "parent") return redirect("/parent/invite");
  // 카카오 로그인만 하고 약관·첫 기관 동의를 안 끝낸 사람은 아직 본 화면에 들어올 수 없다.
  if (!isOnboarded()) return redirect("/parent/invite");
  // 아이 목록(G-10)을 서버에서 못 받는 빌드면 mock 아이를 띄우지 않는다 — 남의 아이가 보인다
  if (!isReady("guardianChildren")) return { kids: null, selectedId: null, hasUnreadInsight: false };
  const kids = await getParentChildren();
  // 동의를 마쳐야만 role이 parent가 되므로 이론상 항상 1명 이상이지만, 방어적으로 처리한다.
  if (kids.length === 0) return redirect("/parent/invite");
  const selectedId = readSelectedChildId(kids);
  // 하단 탭 "리포트"의 점 — 고른 아이에게 아직 열어 보지 않은 리포트가 있는지. 탭은 아이 Context 밖(셸)이라 여기서 센다
  const insights = await loadIfReady("guardianInsights", getGuardianInsights);
  const hasUnreadInsight = (insights ?? []).some((i) => i.childId === selectedId && !i.readAt);
  return { kids, selectedId, hasUnreadInsight };
}

/**
 * 같은 레이아웃 안에서 옮겨 다닐 때 React Router 는 이 loader 를 다시 부르지 않는다. 리포트 점이 어긋나지 않게
 * 아래일 때는 다시 읽는다.
 * - 홈·리포트로 들어갈 때 — 머무는 동안 새 글이 승인됐으면 목록의 "새 글"과 점이 함께 보여야 한다
 * - 리포트 상세에서 나갈 때 — 방금 열어 본 시각이 남아 점이 꺼져야 한다. 설정 링크로 고른 아이도 이때 반영된다
 * 다른 탭으로 옮길 때는 읽지 않는다 — 화면마다 요청을 하나씩 더 하지 않으려는 선택이다.
 */
export function shouldRevalidate({ currentUrl, nextUrl, defaultShouldRevalidate }: ShouldRevalidateFunctionArgs) {
  const isReport = (path: string) => path === "/parent/report" || path.startsWith("/parent/report/");
  return (
    defaultShouldRevalidate ||
    nextUrl.pathname === "/parent" ||
    isReport(nextUrl.pathname) ||
    currentUrl.pathname.startsWith("/parent/report/")
  );
}

export default function ParentGuard() {
  const { kids, selectedId } = useLoaderData<typeof clientLoader>();
  if (!kids) return <ParentNotReady />;
  return (
    <ChildProvider kids={kids} selectedId={selectedId}>
      <Outlet />
    </ChildProvider>
  );
}
