import { redirect } from "react-router";
import { restoreParentOnboarded } from "@/lib/api";
import { announceLoginIfLanded, getSession, homePathFor, readInviteCode, takeLoginIntent, takeReturnPath } from "@/lib/auth";

export async function clientLoader() {
  const intent = takeLoginIntent() ?? (readInviteCode() ? "parent" : null);
  const session = await getSession();

  if (!session.loggedIn) return redirect("/login");
  announceLoginIfLanded(session.userId);
  if (session.withdrawing) return redirect("/withdrawal");
  const back = takeReturnPath(session.role);
  if (!session.signupCompleted || !session.role) {
    return redirect(intent ? `/signup?role=${intent}` : "/signup");
  }
  if (session.role === "parent" && session.termsAgreed) await restoreParentOnboarded();
  const home = homePathFor(session.role, session.termsAgreed);
  const usualHome = session.role === "org" ? "/dashboard" : "/parent";
  return redirect(back && home === usualHome ? back : home);
}

export default function OAuthSuccessRoute() {
  return null;
}
