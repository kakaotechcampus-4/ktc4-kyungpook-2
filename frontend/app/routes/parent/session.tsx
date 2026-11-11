import { Outlet, redirect, type ShouldRevalidateFunctionArgs } from "react-router";
import { getSession, rememberReturnPath } from "@/lib/auth";

export async function clientLoader({ request }: { request: Request }) {
  const session = await getSession();
  const { loggedIn, withdrawing } = session;
  if (request.signal.aborted) return null;
  if (withdrawing) return redirect("/withdrawal");
  if (!loggedIn) {
    rememberReturnPath("parent", new URL(request.url));
    return redirect("/parent/invite");
  }
  return { withdrawalEnabled: session.withdrawalEnabled };
}

export function shouldRevalidate({ currentUrl, nextUrl, defaultShouldRevalidate }: ShouldRevalidateFunctionArgs) {
  return currentUrl.pathname !== nextUrl.pathname || defaultShouldRevalidate;
}

export default function ParentSessionRoute() {
  return <Outlet />;
}
