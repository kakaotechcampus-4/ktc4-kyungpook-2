import { Link, useNavigate } from "react-router";
import { KakaoLoginButton } from "@/components/KakaoLoginButton";
import { grantRole, isAuthMock } from "@/lib/auth";

/**
 * 기관 담당자 로그인 진입점.
 *
 * 기획서 12절의 SMS OTP 는 폐기됐다 — 인증은 기관·보호자 모두 카카오 하나로 통일했다.
 *
 * 버튼은 백엔드의 `/oauth2/authorization/kakao` 로 **페이지를 이동시킨다**. 그 뒤로는
 * 전부 백엔드 몫이다 — state 발급, 카카오 인가, 토큰 교환, 출입증 쿠키 발급까지.
 * 끝나면 `/oauth/success` 로 돌아온다(실패하면 이 화면으로).
 *
 * 보호자도 같은 카카오 로그인을 타지만 진입 화면은 `/parent/invite` 로 따로 둔다 —
 * 돌아왔을 때 기관인지 보호자인지 가릴 단서가 "어느 버튼을 눌렀는가" 뿐이기 때문이다.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const mock = isAuthMock();

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-[420px]">
        <div className="mb-7 flex items-center gap-2.5">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded bg-accent text-[17px] font-bold text-white"
          >
            잇
          </span>
          <div>
            <p className="text-[19px] font-bold tracking-tight">잇다 ITDA</p>
            <p className="text-[14px] text-muted">기관 담당자 로그인</p>
          </div>
        </div>

        <div className="rounded border border-line bg-surface p-6">
          <KakaoLoginButton intent="org" />

          {/*
            데모용 진입. 백엔드 없이 기관 화면을 볼 수 있어야 하는데, 역할을 주는 곳이
            로그인 성공 페이지뿐이라 이것이 없으면 대시보드에 들어갈 방법이 사라진다.
            실연동 빌드(VITE_AUTH_MOCK=false)에서는 렌더링되지 않는다.
          */}
          {mock ? (
            <>
              <div className="my-4 flex items-center gap-3 text-[13px] text-muted">
                <span className="h-px flex-1 bg-line" />
                데모
                <span className="h-px flex-1 bg-line" />
              </div>
              <button
                type="button"
                onClick={() => {
                  grantRole("org");
                  navigate("/dashboard");
                }}
                className="tap w-full rounded border border-line2 px-4 text-[16px] font-semibold text-ink2 hover:bg-surface2"
              >
                mock 데이터로 둘러보기
              </button>
              <p className="mt-2 text-[13px] leading-6 text-muted">
                백엔드 없이 기관 화면을 확인하는 용도입니다.
              </p>
            </>
          ) : null}

          <p className="mt-4 text-[13px] leading-6 text-muted">
            학부모이신가요?{" "}
            <Link to="/parent/invite" className="font-semibold text-accentink underline">
              보호자 화면으로 가기
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
