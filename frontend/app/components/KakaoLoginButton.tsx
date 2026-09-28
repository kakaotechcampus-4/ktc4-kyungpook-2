import { startKakaoLogin } from "@/lib/auth";

/**
 * 카카오 로그인 버튼 — 기관(`/login`)과 보호자(`/parent/invite`)가 함께 쓴다.
 *
 * 두 화면이 같은 카카오 로그인을 타기 때문에, 돌아왔을 때 누구인지 구분할 방법이
 * 눌린 버튼밖에 없다. `intent` 를 여기서 받아 lib/auth 가 기록하고 `/oauth/success`
 * 가 읽는다.
 *
 * 모양만 화면마다 다르다(기관은 각진 버튼, 보호자 앱은 둥근 큰 버튼) — `className`
 * 으로 받는다. 색은 카카오 가이드 값이라 고정이다.
 */
export function KakaoLoginButton({
  intent,
  label = "카카오로 로그인",
  className = "rounded",
}: {
  intent: "org" | "parent";
  label?: string;
  className?: string;
}) {
  return (
    <button
      type="button"
      onClick={() => startKakaoLogin(intent)}
      className={`tap flex w-full items-center justify-center gap-2 bg-[#FEE500] px-4 text-[16px] font-bold text-[#191600] hover:brightness-95 ${className}`}
    >
      <KakaoMark />
      {label}
    </button>
  );
}

function KakaoMark() {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className="size-5 fill-current">
      <path d="M12 3C6.99 3 3 6.2 3 10.14c0 2.52 1.7 4.73 4.26 5.99l-.9 3.3c-.09.32.27.58.55.4l3.96-2.6c.37.03.75.05 1.13.05 5.01 0 9-3.2 9-7.14S17.01 3 12 3Z" />
    </svg>
  );
}
