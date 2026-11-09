import { useState } from "react";
import { useNavigate } from "react-router";
import { Busy } from "@/components/ui";
import { ApiError } from "@/lib/apiError";
import { withdraw, type WithdrawTarget } from "@/lib/auth";

/**
 * 회원 탈퇴 버튼과 확인창. 보호자 설정 · 가입 화면 · 보호자 진입(약관 · 코드 단계)에 둔다 — 가드가 약관 동의 전이거나
 * 아이가 없는 보호자를 설정 화면으로 보내지 않아서, 그 사람들이 닿는 화면마다 길이 있어야 한다.
 *
 * 확인하면 DELETE /auth/me 를 부르고 탈퇴 안내 화면(`/withdrawal`)으로 보낸다. 서버가 끝까지 마쳤으면(204) 완료 표시를
 * 화면 이동 상태(주소가 아니다)로 넘기고, 접수만 됐으면(202) 처리 중으로 간다. 첫 카카오 호출이 실패해 취소되면(502) 이
 * 확인창에 이유를 띄우고 회원은 그대로다.
 *
 * **이 계정만 지운다.** 화면을 열 때 읽은 계정(`account`)을 요청에 싣는다. 확인 창을 연 뒤 다른 탭에서 다른 계정으로
 * 로그인했으면 서버가 409 로 막는다. `account` 가 없으면(서버가 탈퇴를 열지 않았거나 비로그인) 아무것도 그리지 않는다.
 *
 * @param audience 보호자면 아이 연결 · 공유 동의 안내를 함께 보인다. 가입 전이면 가입 정보가 지워진다는 것만 보인다
 * @param trigger  `row` 는 설정 목록의 한 줄, `link` 는 화면 아래 작은 글자 버튼
 * @param account  화면을 열 때 읽은 계정. 요청에 싣는다
 */
export function WithdrawAccount({
  audience,
  trigger = "link",
  account,
}: {
  audience: "parent" | "pending";
  trigger?: "row" | "link";
  account: WithdrawTarget | null;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!account) return null;
  const target = account;

  async function confirm() {
    setPending(true);
    setError(null);
    try {
      const result = await withdraw(target);
      // 완료는 주소가 아니라 이동 상태로 넘긴다 — 주소로 두면 아무나 열어 "탈퇴가 끝났어요" 를 볼 수 있다
      navigate("/withdrawal", { replace: true, state: { completed: result === "completed" } });
    } catch (e) {
      setError(messageFor(e));
      setPending(false);
    }
  }

  return (
    <>
      {trigger === "row" ? (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="tap flex w-full items-center justify-between px-4 py-3.5"
        >
          <span className="text-[15px] text-block">회원 탈퇴</span>
        </button>
      ) : (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="tap mx-auto block px-3 py-2 text-[13px] text-muted underline underline-offset-4"
        >
          회원 탈퇴
        </button>
      )}

      {open ? (
        <div
          role="dialog"
          aria-modal
          aria-labelledby="withdraw-title"
          aria-describedby="withdraw-desc"
          className="fixed inset-0 z-20 flex items-end justify-center bg-ink/40"
        >
          <div className="flex max-h-[90dvh] w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] flex-col rounded-t-3xl bg-surface p-5 pb-7">
            <div className="min-h-0 overflow-y-auto">
              <p id="withdraw-title" className="mb-2 text-[18px] font-extrabold">
                정말 탈퇴할까요?
              </p>
              <ul
                id="withdraw-desc"
                className="mb-5 flex list-disc flex-col gap-1.5 pl-5 text-[15px] leading-7 text-ink2"
              >
                {audience === "parent" ? (
                  <>
                    <li>
                      <b className="font-semibold">연결된 아이와의 연결이 모두 끊겨요.</b>
                    </li>
                    <li>
                      내 공유 동의는 철회돼요. 같은 기관에 다른 보호자의 동의가 남아 있으면 그 기관
                      공유는 계속돼요.
                    </li>
                  </>
                ) : null}
                <li>카카오 연결이 끊기고, 카카오 회원번호가 지워져요.</li>
                <li>
                  같은 카카오 계정으로 다시 들어와도 새로 가입하는 거예요.
                  {audience === "parent" ? " 아이를 다시 연결하려면 기관에서 초대 코드를 새로 받아야 해요." : ""}
                </li>
              </ul>
            </div>
            {error ? (
              <p role="alert" className="mb-3 rounded-2xl bg-blocksoft px-4 py-3 text-[14px] text-block">
                {error}
              </p>
            ) : null}
            <div className="flex gap-2">
              <button
                type="button"
                disabled={pending}
                onClick={() => {
                  setOpen(false);
                  setError(null);
                }}
                className="tap h-14 flex-1 rounded-2xl border border-line2 text-[16px] font-bold text-ink2 disabled:opacity-50"
              >
                취소
              </button>
              <button
                type="button"
                disabled={pending}
                onClick={confirm}
                className="tap h-14 flex-1 rounded-2xl bg-block text-[16px] font-bold text-white disabled:opacity-50"
              >
                {pending ? <Busy>탈퇴하는 중…</Busy> : "탈퇴하기"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "KAKAO_UNLINK_FAILED") {
      return "카카오 연결을 끊지 못해 탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요.";
    }
    if (error.code === "WITHDRAWAL_ACCOUNT_MISMATCH") {
      return "다른 계정으로 로그인이 바뀌었어요. 화면을 새로 고친 뒤 다시 확인해 주세요.";
    }
    if (error.code === "WITHDRAWAL_NOT_AVAILABLE") {
      return "지금은 탈퇴할 수 없어요.";
    }
    if (error.code === "ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED") {
      return "기관 계정은 아직 탈퇴할 수 없어요.";
    }
    if (error.status === 401) {
      return "로그인이 풀렸어요. 다시 로그인한 뒤 해 주세요.";
    }
  }
  return "탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요.";
}
