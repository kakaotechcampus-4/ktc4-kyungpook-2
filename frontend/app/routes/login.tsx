import { useNavigate } from "react-router";
import { grantRole, isAuthMock, startKakaoLogin } from "@/lib/auth";

/**
 * 카카오 로그인 진입점.
 *
 * 기획서 12절의 SMS OTP 는 폐기됐다 — 백엔드 인증이 카카오 OAuth 로 구현돼 있고
 * SMS 발송은 구현 자체가 없다.
 *
 * 버튼은 백엔드의 `/oauth2/authorization/kakao` 로 **페이지를 이동시킨다**. 그 뒤로는
 * 전부 백엔드 몫이다 — state 발급, 카카오 인가, 토큰 교환, 출입증 쿠키 발급까지.
 * 끝나면 `/oauth/success` 로 돌아온다(실패하면 이 화면으로).
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
          <button
            type="button"
            onClick={startKakaoLogin}
            className="tap flex w-full items-center justify-center gap-2 rounded bg-[#FEE500] px-4 text-[16px] font-semibold text-[#191600] hover:brightness-95"
          >
            <KakaoMark />
            카카오로 로그인
          </button>

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
            학부모라면 기관에서 받은 초대 링크로 들어와 주세요.
          </p>
        </div>
      </div>
    </div>
  );
}

function KakaoMark() {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className="size-5 fill-current">
      <path d="M12 3C6.99 3 3 6.2 3 10.14c0 2.52 1.7 4.73 4.26 5.99l-.9 3.3c-.09.32.27.58.55.4l3.96-2.6c.37.03.75.05 1.13.05 5.01 0 9-3.2 9-7.14S17.01 3 12 3Z" />
    </svg>
  );
}
