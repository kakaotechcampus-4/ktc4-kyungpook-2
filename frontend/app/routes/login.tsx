import { useNavigate } from "react-router";
import { buildKakaoAuthorizeUrl, grantRole, isAuthMock, isKakaoConfigured } from "@/lib/auth";

/**
 * 카카오 로그인 진입점.
 *
 * 기획서 12절의 SMS OTP 는 폐기됐다 — BE 인증이 카카오 OAuth + JWT 로 구현돼 있고
 * SMS 발송은 구현 자체가 없다. 두 방식을 함께 두면 어느 쪽이 실제인지 헷갈려서 지웠다.
 *
 * fetch 가 아니라 location 이동인 이유: 인가 화면은 카카오 도메인에서 사용자가 직접
 * 로그인·동의해야 하는 페이지라, 브라우저가 실제로 그리로 가야 한다.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const configured = isKakaoConfigured();
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
            disabled={!configured}
            onClick={() => {
              // state 는 이 시점에 발급·저장된다(lib/oauth-state.ts).
              window.location.href = buildKakaoAuthorizeUrl();
            }}
            className="tap flex w-full items-center justify-center gap-2 rounded bg-[#FEE500] px-4 text-[16px] font-semibold text-[#191600] hover:brightness-95 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <KakaoMark />
            카카오로 로그인
          </button>

          {!configured ? (
            <p className="mt-4 rounded border border-block/40 bg-blocksoft px-3 py-2 text-[13px] leading-6 text-block">
              카카오 키가 설정되지 않았습니다. <code>frontend/.env</code> 의{" "}
              <code>VITE_KAKAO_CLIENT_ID</code> 와 <code>VITE_KAKAO_REDIRECT_URI</code> 를
              채운 뒤 dev 서버를 다시 시작하세요.
            </p>
          ) : null}

          {/*
            데모용 진입. 카카오 키 없이도 기관 화면을 볼 수 있어야 하는데, 역할을 주는 곳이
            카카오 콜백뿐이라 키가 없으면 대시보드에 들어갈 방법이 사라진다.
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
