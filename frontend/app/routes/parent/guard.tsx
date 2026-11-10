import { Outlet, redirect, useLoaderData, type ShouldRevalidateFunctionArgs } from "react-router";
import { ChildProvider } from "@/components/parent/ChildContext";
import { ParentNotReady } from "@/components/parent/NotReady";
import { getSession, rememberReturnPath } from "@/lib/auth";
import { getParentChildren, isReady, restoreParentOnboarded } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader({ request }: { request: Request }) {
  const { loggedIn, signupCompleted, role, termsAgreed, withdrawing } = await getSession();
  if (withdrawing) return redirect("/withdrawal");
  if (loggedIn && !signupCompleted) return redirect("/signup?role=parent");
  if (!loggedIn) {
    rememberReturnPath("parent", new URL(request.url));
    return redirect("/parent/invite");
  }
  if (role !== "parent") return redirect("/parent/invite");
  if (!termsAgreed) return redirect("/parent/invite");
  if (!(await restoreParentOnboarded())) return redirect("/parent/invite");
  if (!isReady("guardianChildren")) return { kids: null, selectedId: null, unreadInsightChildIds: [] as string[] };
  const kids = await getParentChildren();
  if (kids.length === 0) return redirect("/parent/invite");
  const selectedId = readSelectedChildId(kids);
  const unreadInsightChildIds = isReady("guardianInsights")
    ? kids.filter((k) => k.hasUnreadInsight).map((k) => k.id)
    : [];
  return { kids, selectedId, unreadInsightChildIds };
}

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
