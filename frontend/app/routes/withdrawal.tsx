import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router";
import { getSession, signOut } from "@/lib/auth";

/** 처리 중일 때 `/auth/me` 를 다시 읽는 간격. 서버 실행기는 결과를 몰랐던 탈퇴를 1분마다 다시 본다. */
const POLL_MS = 3000;

type State = "checking" | "in_progress" | "done" | "canceled" | "none" | "login_lost";

/**
 * 탈퇴 안내 화면. 탈퇴 확인창이 보내고, 가드들이 탈퇴를 처리하는 중인 회원을 이리 보낸다. 확인창이 실제 204 를 받았을 때만
 * 이동 상태(`completed`)로 완료를 넘긴다 — 주소로 넘기지 않아 직접 열어서는 완료 화면을 볼 수 없다. 그 밖에는 서버에 묻는다.
 * 역할 묶음 밖에 둔다 — 가입 전 사람도 온다.
 *
 * `/auth/me` 를 주기적으로 다시 읽어 결과를 나눈다.
 *  - 401 ACCOUNT_WITHDRAWN: 끝났다. 쿠키는 서버 로그아웃 API 가 지운다(httpOnly 라 프론트가 못 지운다)
 *  - 그 밖의 401: 로그인이 풀렸을 뿐이다. 끝났다고 보여 주지 않는다 — 다시 로그인하면 진행을 볼 수 있다
 *  - withdrawal = in_progress: 계속 기다린다
 *  - withdrawal = none: 탈퇴 확인 창에서 왔으면 첫 카카오 호출이 실패해 접수가 취소된 것이다. 주소로 직접 들어왔으면 진행 중인
 *    탈퇴가 없다는 것뿐이다 — 신청한 적 없는 사람에게 "탈퇴하지 못했어요" 를 보이지 않는다
 */
export default function WithdrawalPage() {
  const navState = useLocation().state as { completed?: boolean } | null;
  const completed = navState?.completed === true;
  // 탈퇴 확인 창이 보낸 것인지. 직접 연 주소에는 이동 상태가 없다
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
          await signOut();
          if (!stopped) setState("done");
          return;
        }
        if (!session.loggedIn) {
          setState("login_lost");
          return;
        }
        if (!session.withdrawing) {
          setState(fromRequest ? "canceled" : "none");
          return;
        }
        setState("in_progress");
      } catch {
        // 서버 장애 · 네트워크 — 결과를 모르니 처리 중으로 두고 다시 묻는다
        if (!stopped) setState("in_progress");
      }
      if (!stopped) timer = setTimeout(check, POLL_MS);
    }

    void check();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
    // 처음 한 번만 시작한다 — 이어서 묻는 일은 check 가 스스로 한다
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
        카카오 연결을 끊고 아이 연결과 공유 동의를 정리하고 있어요. 이 화면을 닫아도 처리는 이어져요.
        끝나기 전까지는 다른 화면을 쓸 수 없어요.
      </p>
    </>
  );
}
