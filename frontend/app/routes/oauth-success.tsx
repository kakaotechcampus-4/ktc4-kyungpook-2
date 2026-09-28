import { redirect } from "react-router";
import { getSession, grantRole, isOnboarded, takeLoginIntent } from "@/lib/auth";

/**
 * 로그인이 끝난 뒤 백엔드가 브라우저를 돌려보내는 곳
 * (백엔드 application.yml 의 `app.auth.success-redirect`).
 *
 * 화면을 그리지 않는다 — 도착하자마자 역할에 맞는 첫 화면으로 보낸다.
 * 출입증은 이미 httpOnly 쿠키로 심겨 있어 프론트가 받아 처리할 것이 없다.
 * (실패는 여기로 오지 않는다. 백엔드가 `/login` 으로 돌려보낸다.)
 *
 * 기관과 보호자가 **같은 카카오 로그인**을 쓰기 때문에, 돌아온 사람이 누구인지는
 * 여기서 정해야 한다. 서버는 알려주지 못한다(사용자 테이블이 없다). 그래서 떠나기 전
 * 눌렀던 버튼(`takeLoginIntent()`)을 기준으로 삼는다.
 *
 * TODO: 서버가 역할을 모른다. GET /api/v1/auth/me 가 생기면 그 응답으로 대체한다.
 */
export async function clientLoader() {
  const intent = takeLoginIntent();
  const { role } = await getSession();

  // 의도 → 기존 역할 → 기관 순. sessionStorage 가 막힌 브라우저에서도 기관은 들어간다.
  const who = intent ?? role ?? "org";

  if (who === "parent") {
    grantRole("parent");
    // 약관 동의와 첫 기관 연결이 남았으면 온보딩으로. 끝났으면 바로 홈.
    return redirect(isOnboarded() ? "/parent" : "/parent/invite");
  }

  grantRole("org");
  return redirect("/dashboard");
}

export default function OAuthSuccessRoute() {
  return null;
}
