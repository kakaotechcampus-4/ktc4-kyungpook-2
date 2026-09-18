import { redirect } from "react-router";
import { getSession, grantRole } from "@/lib/auth";

/**
 * 로그인이 끝난 뒤 백엔드가 브라우저를 돌려보내는 곳
 * (백엔드 application.yml 의 `app.auth.success-redirect`).
 *
 * 화면을 그리지 않는다 — 도착하자마자 역할에 맞는 첫 화면으로 보낸다.
 * 출입증은 이미 httpOnly 쿠키로 심겨 있어 프론트가 받아 처리할 것이 없다.
 * (실패는 여기로 오지 않는다. 백엔드가 `/login` 으로 돌려보낸다.)
 */
export async function clientLoader() {
  const { role } = await getSession();

  // TODO: 서버가 역할을 모른다. GET /api/v1/auth/me 가 생기면 그 응답으로 대체한다.
  //       지금은 기존 역할이 없으면 기관으로 둔다.
  if (!role) {
    grantRole("org");
    return redirect("/dashboard");
  }
  return redirect(role === "parent" ? "/parent" : "/dashboard");
}

export default function OAuthSuccessRoute() {
  return null;
}
