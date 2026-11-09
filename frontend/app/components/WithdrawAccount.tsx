import { useId, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useNavigate } from "react-router";
import { Busy } from "@/components/ui";
import { ApiError } from "@/lib/apiError";
import { getSession, withdraw, type WithdrawTarget } from "@/lib/auth";

/**
 * 회원 탈퇴 버튼과 확인창. 보호자 설정 · 가입 화면 · 보호자 진입(약관 · 코드 단계)에 둔다 — 가드가 약관 동의 전이거나
 * 아이가 없는 보호자를 설정 화면으로 보내지 않아서, 그 사람들이 닿는 화면마다 길이 있어야 한다.
 *
 * 확인하면 DELETE /auth/me 를 부르고 탈퇴 안내 화면(`/withdrawal`)으로 보낸다. 서버가 끝까지 마쳤으면(204) 완료 표시를
 * 화면 이동 상태(주소가 아니다)로 넘기고, 접수만 됐으면(202) 처리 중으로 간다. 첫 카카오 호출이 실패해 취소되면(502) 이
 * 확인창에 이유를 띄우고 회원은 그대로다.
 *
 * **이 계정만 지운다.** 이 버튼이 처음 그려질 때(화면이 그 계정의 내용을 보여 줄 때)의 계정을 고정해 요청에 싣는다.
 * 그 뒤 화면 데이터가 다시 읽혀 `account` 가 다른 계정(다른 탭에서 로그인한 사람)으로 바뀌어도 대상은 바뀌지 않는다 —
 * 바뀐 것을 알아채면 요청을 보내지 않고 새로 고치라고 알린다. 확인 창을 열 때도 서버에 지금 로그인한 계정을 다시 물어 본다.
 * 서버도 지금 로그인한 계정과 다르면 409 로 막는다. 다른 탭에서 로그인하면 앱이 이 탭을 새로 고치므로(reloadOnLoginElsewhere)
 * 화면 데이터와 이 계정은 같은 로그인에서 온다.
 * 처음 그려질 때 `account` 가 없으면(서버가 탈퇴를 열지 않았거나 비로그인) 아무것도 그리지 않는다.
 *
 * 확인창은 약관 본문 창(`TermsSheet`)처럼 네이티브 `<dialog>` 의 `showModal()` 로 띄운다 — 창이 떠 있는 동안 뒤 화면을 누르거나
 * Tab 으로 닿을 수 없어야 한다. 처음 포커스는 [취소]에 둔다(엔터 한 번에 탈퇴가 나가지 않게). 닫으면 [회원 탈퇴] 버튼으로
 * 포커스를 돌려준다.
 *
 * @param audience 보호자면 아이 연결 · 공유 동의 안내를 함께 보인다. 가입 전이면 가입 정보가 지워진다는 것만 보인다
 * @param trigger  `row` 는 설정 목록의 한 줄, `link` 는 화면 아래 작은 글자 버튼
 * @param account  화면 데이터의 계정. 처음 값만 대상으로 쓰고, 이후 값은 바뀌었는지 보는 데만 쓴다
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
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 처음 그려질 때의 계정으로 고정한다. 화면 데이터가 다시 읽혀도 바뀌지 않는다
  const [pinned] = useState<WithdrawTarget | null>(account);
  // 확인 창을 열 때 서버에 물어 본 지금 계정이 고정한 계정과 다른지
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
      // 묻지 못했으면 서버의 409 검사에 맡긴다
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
      // 완료는 주소가 아니라 이동 상태로 넘긴다 — 주소로 두면 아무나 열어 "탈퇴가 끝났어요" 를 볼 수 있다
      // 탈퇴를 요청한 계정도 넘긴다 — 안내 화면이 결과를 물을 때 그 사이 다른 계정으로 바뀌었는지 본다
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

/** 탈퇴 확인창. 띄울 때만 그린다(닫으면 부르는 쪽이 지운다). */
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

  // 레이아웃 효과라 지울 때 dialog 가 문서에서 빠지기 전에 닫힌다
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
      // 탈퇴를 마쳐 다른 화면으로 옮겨 가면 버튼도 함께 사라진다. 남아 있을 때만 돌려준다
      if (trigger?.isConnected) trigger.focus();
    };
  }, [returnFocusTo]);

  // 네이티브 모달은 Tab 이 브라우저 주소창으로도 나가므로 첫 · 끝 요소에서 감싸 돈다
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
        // Esc. 탈퇴를 보내는 중에는 닫지 않는다
        e.preventDefault();
        onCancel();
      }}
      // Esc 를 잇달아 누르면 브라우저가 cancel 없이 닫기도 한다. close 이벤트는 늦게 오므로 그사이 다시 열렸으면
      // (개발 모드는 효과를 두 번 돌려 닫고 다시 연다) 무시한다. 탈퇴를 보내는 중이면 결과를 보여 줄 수 있게 다시 띄운다
      onClose={() => {
        const dialog = ref.current;
        if (!dialog || dialog.open) return;
        if (pending) dialog.showModal();
        else onCancel();
      }}
      onKeyDown={trapTab}
      onClick={(e) => {
        // 창 바깥(배경막)을 누르면 이벤트 대상이 dialog 자신이다
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
