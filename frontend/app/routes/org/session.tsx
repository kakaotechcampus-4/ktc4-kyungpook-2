import { Outlet, redirect, type ShouldRevalidateFunctionArgs } from "react-router";
import { getSession, rememberReturnPath } from "@/lib/auth";

export async function clientLoader({ request }: { request: Request }) {
  const { loggedIn } = await getSession();
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
