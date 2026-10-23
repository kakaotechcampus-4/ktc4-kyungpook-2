import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { signOut } from "@/lib/auth";

/**
 * 실연동에서 보호자 본 화면(홈 · 타임라인 · 리포트 · 설정)이 아직 서버에 없을 때.
 *
 * 이 화면들은 연결된 아이 목록(G-10)부터 받아야 하는데 그 API 가 없다. mock 아이를 띄우면
 * 동의까지 마친 실제 보호자에게 남의 아이가 보이므로, 연결은 끝났다는 것만 알린다.
 * 로그아웃을 같이 두는 이유: 이 화면에서 갈 곳이 없고, 다른 카카오 계정으로 바꿔 시험해 볼 수 있어야 한다.
 */
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
        {signingOut ? "로그아웃 중…" : "로그아웃"}
      </button>
    </div>
  );
}
