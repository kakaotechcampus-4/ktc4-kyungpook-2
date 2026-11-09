import { redirect } from "react-router";
import { restoreParentOnboarded } from "@/lib/api";
import { announceLogin, getSession, homePathFor, readInviteCode, takeLoginIntent, takeReturnPath } from "@/lib/auth";

/**
 * 로그인이 끝난 뒤 백엔드가 브라우저를 돌려보내는 곳
 * (백엔드 application.yml 의 `app.auth.success-redirect`).
 *
 * 화면을 그리지 않는다 — 도착하자마자 `/auth/me` 를 물어 갈 곳을 정한다.
 * 출입증은 이미 httpOnly 쿠키로 심겨 있어 프론트가 받아 처리할 것이 없다.
 * (실패는 여기로 오지 않는다. 백엔드가 `/login` 으로 돌려보낸다.)
 *
 *  - 가입 완료: 서버가 알려준 역할의 첫 화면으로. 약관에 동의하지 않았거나 초대 링크로 받아 둔
 *    코드가 있는 보호자는 `homePathFor` 가 온보딩 화면(`/parent/invite`)으로 보낸다. 약관에 동의했는데
 *    이 브라우저에 온보딩 표시가 없는 보호자는 서버에 연결된 아이가 있는지 먼저 본다 (restoreParentOnboarded)
 *  - 가입 미완료(처음 로그인한 사람): 회원가입 화면으로. 떠나기 전 눌렀던 버튼
 *    (`takeLoginIntent()`)은 그 화면에서 역할을 미리 골라두는 데만 쓴다. 초대 코드를
 *    들고 왔으면 보호자일 테니 보호자를 골라 둔다
 *  - 로그인이 풀려 로그인 화면으로 갔던 사람: 가드가 남긴 주소(`takeReturnPath`)로 돌려보낸다. 역할이 같고
 *    첫 화면이 보통 첫 화면(기관 `/dashboard` · 보호자 `/parent`)일 때만이다
 */
export async function clientLoader() {
  const intent = takeLoginIntent() ?? (readInviteCode() ? "parent" : null);
  const session = await getSession();

  if (!session.loggedIn) return redirect("/login");
  // 이 브라우저의 다른 탭들에 로그인이 바뀌었다고 알린다 — 그 탭들은 화면을 새로 고친다
  announceLogin();
  // 탈퇴를 처리하는 중이면 서버가 다른 API 를 막는다 — 가입 · 약관보다 먼저 본다
  if (session.withdrawing) return redirect("/withdrawal");
  // 로그인이 풀려서 남겨 둔 주소는 여기서 한 번 읽고 지운다 — 쓰지 않는 경우에도 남겨 두면 다음 로그인 화면에 만료 안내가 뜬다
  const back = takeReturnPath(session.role);
  if (!session.signupCompleted || !session.role) {
    return redirect(intent ? `/signup?role=${intent}` : "/signup");
  }
  // 다른 브라우저에서 로그인한 보호자도 서버에 아이가 있으면 코드 입력 대신 홈으로 간다.
  // 약관 동의 전에는 묻지 않는다 — 아이 목록이 403 TERMS_NOT_AGREED 다
  if (session.role === "parent" && session.termsAgreed) await restoreParentOnboarded();
  const home = homePathFor(session.role, session.termsAgreed);
  // 가입 · 약관 · 온보딩이 다 끝난 사람(보통 첫 화면으로 갈 사람)만 남긴 주소로 돌려보낸다. 남은 단계가 있으면 그 화면이 먼저다
  const usualHome = session.role === "org" ? "/dashboard" : "/parent";
  return redirect(back && home === usualHome ? back : home);
}

export default function OAuthSuccessRoute() {
  return null;
}
