import { Link, redirect, useLoaderData } from "react-router";
import type { ClientLoaderFunctionArgs } from "react-router";
import { exchangeKakaoCode, getSession, grantRole } from "@/lib/auth";
import { consumeState } from "@/lib/oauth-state";

/**
 * 카카오가 인가 코드를 돌려주는 곳. `kakao.redirect-uri` 가 가리키는 주소다.
 *
 * 순서가 중요하다 — **state 검증을 통과하기 전에는 code 를 서버로 보내지 않는다.**
 * 먼저 보내고 나서 검증하면 CSRF 를 막는 의미가 사라진다.
 *
 * useEffect 가 아니라 clientLoader 를 쓰는 이유: 인가 코드는 **한 번만** 쓸 수 있는데,
 * StrictMode 의 이펙트 이중 실행에 걸리면 두 번째 교환이 반드시 실패한다.
 * clientLoader 는 진입당 한 번만 돈다.
 */
export async function clientLoader({ request }: ClientLoaderFunctionArgs) {
  const params = new URL(request.url).searchParams;

  // 사용자가 동의 화면에서 취소한 경우. 에러가 아니라 정상적인 이탈이다.
  const error = params.get("error");
  if (error) {
    return {
      message:
        error === "access_denied"
          ? "카카오 로그인을 취소했습니다."
          : `카카오에서 로그인을 거절했습니다. (${error})`,
      detail: params.get("error_description"),
    };
  }

  // state 부터. 실패하면 code 를 손도 대지 않고 버린다.
  if (!consumeState(params.get("state"))) {
    return {
      message: "로그인 요청을 확인할 수 없어 중단했습니다.",
      detail:
        "이 브라우저에서 시작한 로그인이 맞는지 확인하지 못했습니다. 로그인 화면에서 다시 시도해 주세요.",
    };
  }

  const code = params.get("code");
  if (!code) {
    return { message: "인가 코드가 없습니다.", detail: null };
  }

  try {
    await exchangeKakaoCode(code);
  } catch (e) {
    return {
      message: "로그인에 실패했습니다.",
      detail: e instanceof Error ? e.message : null,
    };
  }

  // TODO: 역할은 BE 에 GET /api/v1/auth/me 가 생기면 서버 값으로 대체한다.
  //       지금은 서버가 기관/학부모를 구분하지 못해, 기존 역할이 없으면 기관으로 둔다.
  const { role } = await getSession();
  if (!role) grantRole("org");
  return redirect(role === "parent" ? "/parent" : "/dashboard");
}

export default function KakaoCallbackRoute() {
  const { message, detail } = useLoaderData<typeof clientLoader>();

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-[420px] rounded border border-line bg-surface p-6">
        <p className="text-[17px] font-bold tracking-tight">{message}</p>
        {detail ? <p className="mt-2 text-[14px] leading-6 text-muted">{detail}</p> : null}
        <Link
          to="/login"
          className="tap mt-5 flex items-center justify-center rounded bg-accent px-4 text-[16px] font-semibold text-white hover:bg-accentink"
        >
          로그인 화면으로
        </Link>
      </div>
    </div>
  );
}
