import { redirect } from "react-router";

export async function clientLoader() {
  return redirect("/parent/settings");
}

export default function ParentConsentManageRedirect() {
  return null;
}
