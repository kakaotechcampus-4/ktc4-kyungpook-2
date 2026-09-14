import { redirect } from "react-router";

/** 기관 권한 관리 UI는 설정(/parent/settings)으로 통합됐다 — 옛 경로만 리다이렉트로 남긴다. */
export async function clientLoader() {
  return redirect("/parent/settings");
}

export default function ParentConsentManageRedirect() {
  return null;
}
