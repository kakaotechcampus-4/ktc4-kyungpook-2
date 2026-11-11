import { Outlet, redirect, useLoaderData } from "react-router";
import { skipSearchOnlyChange } from "@/lib/revalidate";
import { loadOrgLive, OrgShell } from "@/components/org/Shell";
import { getSession, isAuthMock, rememberReturnPath } from "@/lib/auth";
import { MY_INSTITUTION } from "@/lib/mock/data";
import "./org.css";

export const shouldRevalidate = skipSearchOnlyChange;

export async function clientLoader({ request }: { request: Request }) {
  const { loggedIn, signupCompleted, role, institutionName, termsAgreed, withdrawing, withdrawn, withdrawalEnabled } =
    await getSession();
  if (withdrawing || withdrawn) return redirect("/withdrawal");
  if (loggedIn && !signupCompleted) return redirect("/signup?role=org");
  if (!loggedIn) {
    rememberReturnPath("org", new URL(request.url));
    return redirect("/login");
  }
  if (role !== "org") return redirect("/login");
  if (!termsAgreed) return redirect("/org-terms");

  const live = await loadOrgLive();

  const me = { institutionName: isAuthMock() ? MY_INSTITUTION.name : (institutionName ?? null), withdrawalEnabled };

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
