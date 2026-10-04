import { redirect } from "react-router";
import { getSession, homePathFor, takeLoginIntent } from "@/lib/auth";

/**
 * 로그인이 끝난 뒤 백엔드가 브라우저를 돌려보내는 곳
 * (백엔드 application.yml 의 `app.auth.success-redirect`).
 *
 * 화면을 그리지 않는다 — 도착하자마자 `/auth/me` 를 물어 갈 곳을 정한다.
 * 출입증은 이미 httpOnly 쿠키로 심겨 있어 프론트가 받아 처리할 것이 없다.
 * (실패는 여기로 오지 않는다. 백엔드가 `/login` 으로 돌려보낸다.)
 *
 *  - 가입 완료: 서버가 알려준 역할의 첫 화면으로
 *  - 가입 미완료(처음 로그인한 사람): 회원가입 화면으로. 떠나기 전 눌렀던 버튼
 *    (`takeLoginIntent()`)은 그 화면에서 역할을 미리 골라두는 데만 쓴다
 */
export async function clientLoader() {
  const intent = takeLoginIntent();
  const session = await getSession();

  if (!session.loggedIn) return redirect("/login");
  if (!session.signupCompleted || !session.role) {
    return redirect(intent ? `/signup?role=${intent}` : "/signup");
  }
  return redirect(homePathFor(session.role));
}

export default function OAuthSuccessRoute() {
  return null;
}
