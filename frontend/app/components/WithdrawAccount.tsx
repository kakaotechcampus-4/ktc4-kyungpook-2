import { useId, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useNavigate } from "react-router";
import { Busy } from "@/components/ui";
import { ApiError } from "@/lib/apiError";
import { getSession, withdraw, type WithdrawTarget } from "@/lib/auth";

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
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pinned] = useState<WithdrawTarget | null>(account);
  const [changedOnOpen, setChangedOnOpen] = useState(false);

  if (!pinned) return null;
  const target = pinned;
  const accountChanged = account?.userId !== target.userId || changedOnOpen;

  async function openDialog() {
    setOpen(true);
    setError(null);
    setChangedOnOpen(false);
    try {
      const now = await getSession();
      if (now.userId !== target.userId) setChangedOnOpen(true);
    } catch {
    }
  }

  function closeDialog() {
    if (pending) return;
    setOpen(false);
    setError(null);
  }

  async function confirm() {
    if (accountChanged) {
      setError(ACCOUNT_CHANGED);
      return;
    }
    setPending(true);
    setError(null);
    try {
      const result = await withdraw(target);
      navigate("/withdrawal", {
        replace: true,
        state: { completed: result === "completed", userId: target.userId },
      });
    } catch (e) {
      setError(messageFor(e));
      setPending(false);
    }
  }

  return (
    <>
      {trigger === "row" ? (
        <button
          ref={triggerRef}
          type="button"
          onClick={() => void openDialog()}
          className="tap flex w-full items-center justify-between px-4 py-3.5"
        >
          <span className="text-[15px] text-block">회원 탈퇴</span>
        </button>
      ) : (
        <button
          ref={triggerRef}
          type="button"
          onClick={() => void openDialog()}
          className="tap mx-auto block px-3 py-2 text-[13px] text-muted underline underline-offset-4"
        >
          회원 탈퇴
        </button>
      )}

      {open ? (
        <WithdrawDialog
          audience={audience}
          message={error ?? (accountChanged ? ACCOUNT_CHANGED : null)}
          pending={pending}
          confirmDisabled={pending || accountChanged}
          returnFocusTo={triggerRef}
          onCancel={closeDialog}
          onConfirm={() => void confirm()}
        />
      ) : null}
    </>
  );
}

const FOCUSABLE = 'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

function WithdrawDialog({
  audience,
  message,
  pending,
  confirmDisabled,
  returnFocusTo,
  onCancel,
  onConfirm,
}: {
  audience: "parent" | "pending";
  message: string | null;
  pending: boolean;
  confirmDisabled: boolean;
  returnFocusTo: RefObject<HTMLButtonElement | null>;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const descId = useId();

  useLayoutEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const trigger = returnFocusTo.current;
    dialog.showModal();
    cancelRef.current?.focus();
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
      if (dialog.open) dialog.close();
      if (trigger?.isConnected) trigger.focus();
    };
  }, [returnFocusTo]);

  function trapTab(e: React.KeyboardEvent<HTMLDialogElement>) {
    if (e.key !== "Tab" || !ref.current) return;
    const items = [...ref.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={descId}
      onCancel={(e) => {
        e.preventDefault();
        onCancel();
      }}
      onClose={() => {
        const dialog = ref.current;
        if (!dialog || dialog.open) return;
        if (pending) dialog.showModal();
        else onCancel();
      }}
      onKeyDown={trapTab}
      onClick={(e) => {
        if (e.target === e.currentTarget) onCancel();
      }}
      className="mx-auto mt-auto mb-0 max-h-[90dvh] w-full max-w-[430px] flex-col rounded-t-3xl bg-surface p-5 pb-7 text-ink backdrop:bg-ink/40 open:flex md:max-w-[560px] lg:max-w-[640px]"
    >
      <div className="min-h-0 overflow-y-auto">
        <p id={titleId} className="mb-2 text-[18px] font-extrabold">
          정말 탈퇴할까요?
        </p>
        <ul id={descId} className="mb-5 flex list-disc flex-col gap-1.5 pl-5 text-[15px] leading-7 text-ink2">
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
      {message ? (
        <p role="alert" className="mb-3 rounded-2xl bg-blocksoft px-4 py-3 text-[14px] text-block">
          {message}
        </p>
      ) : null}
      <div className="flex gap-2">
        <button
          ref={cancelRef}
          type="button"
          disabled={pending}
          onClick={onCancel}
          className="tap h-14 flex-1 rounded-2xl border border-line2 text-[16px] font-bold text-ink2 disabled:opacity-50"
        >
          취소
        </button>
        <button
          type="button"
          disabled={confirmDisabled}
          onClick={onConfirm}
          className="tap h-14 flex-1 rounded-2xl bg-block text-[16px] font-bold text-white disabled:opacity-50"
        >
          {pending ? <Busy>탈퇴하는 중…</Busy> : "탈퇴하기"}
        </button>
      </div>
    </dialog>
  );
}

const ACCOUNT_CHANGED = "다른 계정으로 로그인이 바뀌었어요. 화면을 새로 고친 뒤 다시 확인해 주세요.";

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "KAKAO_UNLINK_FAILED") {
      return "카카오 연결을 끊지 못해 탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요.";
    }
    if (error.code === "WITHDRAWAL_ACCOUNT_MISMATCH") {
      return ACCOUNT_CHANGED;
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
