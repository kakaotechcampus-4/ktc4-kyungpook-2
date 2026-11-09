import { Outlet, redirect, useLoaderData, type ShouldRevalidateFunctionArgs } from "react-router";
import { ChildProvider } from "@/components/parent/ChildContext";
import { ParentNotReady } from "@/components/parent/NotReady";
import { getSession, rememberReturnPath } from "@/lib/auth";
import { getParentChildren, isReady, restoreParentOnboarded } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

/**
 * proxy.ts 의 PARENT_PREFIX 게이트를 대체한다. /parent/invite, /parent/consent 는
 * 이 guard 밖(parent/shell.tsx 아래 직속)에 있어 로그인 없이 접근 가능하다.
 *
 * 여기서 아이 목록도 함께 읽어서 ChildProvider 로 내려준다 — 아이 전환은
 * localStorage 에 쓰고 revalidate() 하는 방식이라, 이 clientLoader 가
 * 그 값을 매번 다시 읽어오는 지점이 된다.
 */
export async function clientLoader({ request }: { request: Request }) {
  const { loggedIn, signupCompleted, role, termsAgreed, withdrawing } = await getSession();
  // 탈퇴를 처리하는 중이면 서버가 아이 목록 등을 409 로 막는다 — 가입 · 약관 · 아이 목록보다 먼저 본다
  if (withdrawing) return redirect("/withdrawal");
  if (loggedIn && !signupCompleted) return redirect("/signup?role=parent");
  // 로그인이 풀렸으면 다시 로그인한 뒤 이 화면으로 돌아오게 주소를 남긴다(보호자 로그인은 진입 화면에서 한다)
  if (!loggedIn) {
    rememberReturnPath("parent", new URL(request.url));
    return redirect("/parent/invite");
  }
  if (role !== "parent") return redirect("/parent/invite");
  // 약관에 동의하지 않은 보호자는 서버가 보호 API 를 모두 막는다 — 아이 목록을 묻기 전에 약관 단계로
  if (!termsAgreed) return redirect("/parent/invite");
  // 첫 기관 동의를 안 끝낸 사람은 아직 본 화면에 들어올 수 없다.
  // 이 브라우저에 표시가 없어도 서버에 연결된 아이가 있으면 마친 사람이다 (다른 브라우저 · 기기)
  if (!(await restoreParentOnboarded())) return redirect("/parent/invite");
  // 아이 목록(G-10)을 서버에서 못 받는 빌드면 mock 아이를 띄우지 않는다 — 남의 아이가 보인다
  if (!isReady("guardianChildren")) return { kids: null, selectedId: null, unreadInsightChildIds: [] as string[] };
  const kids = await getParentChildren();
  // 동의를 마쳐야만 role이 parent가 되므로 이론상 항상 1명 이상이지만, 방어적으로 처리한다.
  if (kids.length === 0) return redirect("/parent/invite");
  const selectedId = readSelectedChildId(kids);
  // 아직 열어 보지 않은 리포트가 있는 아이. 두 점이 이 값 하나에서 나온다 — 하단 리포트 탭(고른 아이, 셸이 아이
  // Context 밖이라 이 loader 를 직접 읽는다)과 헤더의 아이 전환(다른 아이, Context 로 받는다).
  // 리포트 전부를 받지 않고 아이 목록의 표시로 정한다 (#457). "특이사항 없음" 주는 서버가 세지 않는다
  const unreadInsightChildIds = isReady("guardianInsights")
    ? kids.filter((k) => k.hasUnreadInsight).map((k) => k.id)
    : [];
  return { kids, selectedId, unreadInsightChildIds };
}

/**
 * 같은 레이아웃 안에서 옮겨 다닐 때 React Router 는 이 loader 를 다시 부르지 않는다. 리포트 점이 어긋나지 않게
 * 아래일 때는 다시 읽는다.
 * - 홈·리포트로 들어갈 때 — 머무는 동안 새 글이 승인됐으면 목록의 "새 글"과 점이 함께 보여야 한다
 * - 리포트 상세에서 나갈 때 — 방금 열어 본 시각이 남아 점이 꺼져야 한다. 설정 링크로 고른 아이도 이때 반영된다
 * 다른 탭으로 옮길 때는 읽지 않는다 — 화면마다 요청을 하나씩 더 하지 않으려는 선택이다.
 *
 * 다시 읽는 것은 아이 목록 하나다 (#457). 홈 · 리포트 화면도 같은 목록을 물어 응답 하나를 같이 쓴다
 * (lib/parentChildrenCache). 상세를 열면 그 목록을 버려서 나갈 때는 새 표시를 받는다.
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
  const { kids, selectedId, unreadInsightChildIds } = useLoaderData<typeof clientLoader>();
  if (!kids) return <ParentNotReady />;
  return (
    <ChildProvider kids={kids} selectedId={selectedId} unreadInsightChildIds={unreadInsightChildIds}>
      <Outlet />
    </ChildProvider>
  );
}
