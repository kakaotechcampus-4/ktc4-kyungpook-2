import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router";
import { forgetLocalSession, getSession } from "@/lib/auth";

const POLL_MS = 3000;

type State = "checking" | "in_progress" | "done" | "canceled" | "none" | "login_lost" | "other_account";

export default function WithdrawalPage() {
  const navState = useLocation().state as { completed?: boolean; userId?: string } | null;
  const requestedUserId = navState?.userId ?? null;
  const completed = navState?.completed === true;
  const fromRequest = navState !== null && typeof navState === "object" && "completed" in navState;
  const [state, setState] = useState<State>(completed ? "done" : "checking");

  useEffect(() => {
    if (state !== "checking") return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function check() {
      try {
        const session = await getSession();
        if (stopped) return;
        if (session.withdrawn) {
          forgetLocalSession();
          setState("done");
          return;
        }
        if (!session.loggedIn) {
          setState("login_lost");
          return;
        }
        if (requestedUserId && session.userId !== requestedUserId) {
          setState("other_account");
          return;
        }
        if (!session.withdrawing) {
          setState(fromRequest ? "canceled" : "none");
          return;
        }
        setState("in_progress");
      } catch {
        if (!stopped) setState("in_progress");
      }
      if (!stopped) timer = setTimeout(check, POLL_MS);
    }

    void check();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      className="flex min-h-screen items-center justify-center px-4 py-10 break-keep"
      style={{
        background:
          "radial-gradient(620px 380px at 100% 0%, #fff0b4 0%, rgb(255 240 180 / 0) 64%)," +
          "radial-gradient(560px 460px at 0% 100%, #ddf2e3 0%, rgb(221 242 227 / 0) 64%)," +
          "var(--color-paper)",
      }}
    >
      <div className="w-full max-w-[460px]">
        <div className="mb-7 flex flex-col items-center gap-1.5 text-center">
          <img src="/img/logo.png" alt="잇다" width={600} height={220} className="h-auto w-[116px]" />
          <p className="text-[14px] text-muted">회원 탈퇴</p>
        </div>
        <div
          role="status"
          aria-live="polite"
          className="flex flex-col gap-4 rounded-[22px] border border-line bg-surface p-6 text-center shadow-[0_10px_30px_rgba(120,90,40,0.07)]"
        >
          <Content state={state} />
        </div>
      </div>
    </div>
  );
}

function Content({ state }: { state: State }) {
  if (state === "done") {
    return (
      <>
        <h1 className="text-[20px] font-bold">탈퇴가 끝났어요</h1>
        <p className="text-[15px] leading-7 text-ink2">
          그동안 이용해 주셔서 고마워요. 같은 카카오 계정으로 다시 들어오면 새로 가입하게 돼요.
        </p>
        <Link to="/login" replace className="tap flex items-center justify-center rounded bg-ink px-4 text-[16px] font-semibold text-paper">
          처음 화면으로
        </Link>
      </>
    );
  }
  if (state === "canceled") {
    return (
      <>
        <h1 className="text-[20px] font-bold">탈퇴하지 못했어요</h1>
        <p className="text-[15px] leading-7 text-ink2">
          카카오 연결을 끊지 못해 탈퇴를 멈췄어요. 계정은 그대로예요. 잠시 뒤 다시 해 주세요.
        </p>
        <Link to="/oauth/success" replace className="tap flex items-center justify-center rounded bg-ink px-4 text-[16px] font-semibold text-paper">
          돌아가기
        </Link>
      </>
    );
  }
  if (state === "other_account") {
    return (
      <>
        <h1 className="text-[20px] font-bold">다른 계정으로 로그인돼 있어요</h1>
        <p className="text-[15px] leading-7 text-ink2">
          그 사이 다른 계정으로 로그인이 바뀌어, 탈퇴를 신청한 계정의 결과를 여기서 볼 수 없어요. 신청한 탈퇴는 서버가 이어서
          처리해요.
        </p>
      </>
    );
  }
  if (state === "none") {
    return (
      <>
        <h1 className="text-[20px] font-bold">진행 중인 탈퇴가 없어요</h1>
        <p className="text-[15px] leading-7 text-ink2">계정은 그대로예요.</p>
        <Link
          to="/oauth/success"
          replace
          className="tap flex items-center justify-center rounded bg-ink px-4 text-[16px] font-semibold text-paper"
        >
          돌아가기
        </Link>
      </>
    );
  }
  if (state === "login_lost") {
    return (
      <>
        <h1 className="text-[20px] font-bold">로그인이 풀렸어요</h1>
        <p className="text-[15px] leading-7 text-ink2">
          탈퇴가 끝났는지 여기서는 알 수 없어요. 다시 로그인하면 진행 상태를 볼 수 있어요. 다시 로그인했을 때
          가입 화면이 나오면 탈퇴가 끝난 거예요.
        </p>
        <Link to="/login" replace className="tap flex items-center justify-center rounded bg-ink px-4 text-[16px] font-semibold text-paper">
          다시 로그인
        </Link>
      </>
    );
  }
  return (
    <>
      <h1 className="text-[20px] font-bold">탈퇴를 처리하고 있어요</h1>
      <p className="text-[15px] leading-7 text-ink2">
        카카오 연결을 끊고 계정 정보를 정리하고 있어요(보호자는 아이 연결과 공유 동의도 함께). 이 화면을 닫아도 처리는 이어져요.
        끝나기 전까지는 다른 화면을 쓸 수 없어요.
      </p>
    </>
  );
}
