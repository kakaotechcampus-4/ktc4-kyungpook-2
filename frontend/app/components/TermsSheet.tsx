import { useId, useLayoutEffect, useRef } from "react";
import { X } from "lucide-react";
import { TermsArticle } from "@/components/TermsArticle";
import type { TermsDocument } from "@/lib/auth";

const FOCUSABLE = 'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

/**
 * 약관 동의 화면에서 [보기]로 여는 본문 창. 띄울 때만 그린다(닫으면 부르는 쪽이 지운다).
 *
 * 공용 팝업(`ListPopup`)이 아니라 네이티브 `<dialog>` 의 `showModal()` 을 쓴다 — 동의 화면은 창 뒤가
 * [동의하고 계속하기]라, 창이 떠 있는 동안 뒤 화면을 누르거나 Tab 으로 닿을 수 없어야 한다. `showModal()` 이 뒤를
 * 막고 Esc 를 받는다. 네이티브 모달은 Tab 이 브라우저 주소창으로도 나가므로 첫 · 끝 요소에서 감싸 돈다.
 * 모양 · 520px 이하 아래 시트 · 등장 움직임은 `ListPopup` 과 같게 맞췄다.
 */
export function TermsSheet({ doc, onClose }: { doc: TermsDocument; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  // 레이아웃 효과라 지울 때 dialog 가 문서에서 빠지기 전에 닫힌다
  useLayoutEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    dialog.showModal();
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
      if (dialog.open) dialog.close();
    };
  }, []);

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
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      // Esc 를 잇달아 누르면 브라우저가 cancel 없이 닫기도 한다. close 이벤트는 늦게 오므로 그사이 다시 열렸으면
      // (개발 모드는 효과를 두 번 돌려 닫고 다시 연다) 무시한다
      onClose={() => {
        if (!ref.current?.open) onClose();
      }}
      onKeyDown={trapTab}
      onClick={(e) => {
        // 창 바깥(배경막)을 누르면 이벤트 대상이 dialog 자신이다
        if (e.target === e.currentTarget) onClose();
      }}
      className="m-auto max-h-[calc(100%-48px)] w-[calc(100%-32px)] max-w-[640px] animate-pop-up flex-col overflow-hidden rounded-[22px] bg-surface p-0 text-ink shadow-[0_24px_60px_rgb(39_36_31/0.28)] backdrop:bg-[rgb(39_36_31/0.4)] open:flex motion-reduce:animate-none max-[520px]:mb-0 max-[520px]:max-h-[92%] max-[520px]:w-full max-[520px]:max-w-full max-[520px]:rounded-b-none"
    >
      <header className="flex items-center gap-3 border-b border-line px-5 pt-[18px] pb-3.5">
        <h2 id={titleId} className="min-w-0 flex-1 text-[18px] font-extrabold tracking-[-0.02em] text-balance">
          {doc.title}
        </h2>
        <button
          type="button"
          onClick={onClose}
          aria-label="닫기"
          className="tap grid size-10 flex-none place-items-center rounded-full text-ink2 hover:bg-surface2"
        >
          <X aria-hidden size={20} strokeWidth={2.2} />
        </button>
      </header>
      {/* 키보드로도 본문을 굴릴 수 있게 Tab 이 닿는다 */}
      <div
        tabIndex={0}
        className="min-h-0 flex-auto overflow-y-auto overscroll-contain px-[22px] pt-[18px] pb-[22px] focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent"
      >
        <TermsArticle doc={doc} />
      </div>
    </dialog>
  );
}
