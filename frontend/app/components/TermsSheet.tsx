import { useId, useLayoutEffect, useRef } from "react";
import { X } from "lucide-react";
import { TermsArticle } from "@/components/TermsArticle";
import type { TermsDocument } from "@/lib/auth";

const FOCUSABLE = 'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

export function TermsSheet({ doc, onClose }: { doc: TermsDocument; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

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
      onClose={() => {
        if (!ref.current?.open) onClose();
      }}
      onKeyDown={trapTab}
      onClick={(e) => {
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
      <div
        tabIndex={0}
        className="min-h-0 flex-auto overflow-y-auto overscroll-contain px-[22px] pt-[18px] pb-[22px] focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent"
      >
        <TermsArticle doc={doc} />
      </div>
    </dialog>
  );
}
