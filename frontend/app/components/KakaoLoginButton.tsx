import { startKakaoLogin } from "@/lib/auth";

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
