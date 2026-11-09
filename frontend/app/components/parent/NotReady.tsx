import { useState } from "react";
import { Busy } from "@/components/ui";
import { Link, useNavigate } from "react-router";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { signOut } from "@/lib/auth";

export function ParentNotReady() {
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);

  async function logout() {
    setSigningOut(true);
    await signOut();
    navigate("/login", { replace: true });
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col items-center gap-2 rounded-2xl border border-line px-5 py-10 text-center">
        <span
          aria-hidden
          className="flex size-12 items-center justify-center rounded-full bg-accentsoft text-[22px] text-accentink"
        >
          ✓
        </span>
        <h1 className="text-[20px] font-bold">기관과 연결이 끝났어요</h1>
        <p className="max-w-xs text-[15px] leading-6 text-muted">
          선생님이 기록을 올리고 검토하면 이곳에서 아이의 하루를 볼 수 있어요. 지금은 이 화면을
          준비하고 있어요.
        </p>
      </div>

      <Link
        to="/parent/invite"
        className="tap flex items-center justify-center rounded-2xl border border-dashed border-line2 px-4 text-[15px] font-semibold text-accentink hover:border-accent"
      >
        다른 초대 코드 입력하기
      </Link>
      <button
        type="button"
        onClick={logout}
        disabled={signingOut}
        className="tap rounded-2xl px-4 text-[14px] font-semibold text-muted underline disabled:opacity-60"
      >
        {signingOut ? <Busy>로그아웃 중…</Busy> : "로그아웃"}
      </button>
    </div>
  );
}

export function ParentNotReadyPage({
  title,
  back = false,
  showChildSwitch = false,
}: {
  title: string;
  back?: boolean;
  showChildSwitch?: boolean;
}) {
  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title={title} back={back} showChildSwitch={showChildSwitch} />
      <div className="flex flex-col items-center gap-2 rounded-2xl border border-dashed border-line2 px-5 py-10 text-center">
        <p className="text-[17px] font-bold">준비 중인 기능이에요</p>
        <p className="max-w-xs text-[15px] leading-6 text-muted">
          서버 연결을 준비하고 있어요. 연결되면 이 화면에서 바로 볼 수 있어요.
        </p>
      </div>
    </div>
  );
}

export function ParentNotReadySection() {
  return (
    <p className="rounded-2xl border border-dashed border-line2 px-4 py-6 text-center text-[14px] text-muted">
      준비 중인 기능이에요. 서버 연결을 준비하고 있어요.
    </p>
  );
}
