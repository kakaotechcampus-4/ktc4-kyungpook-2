import { useEffect, useId, useRef, useState } from "react";
import { HelpCircle, X } from "lucide-react";

/**
 * 카드 모서리의 물음표 — 눌러야 보이는 설명.
 *
 * 처음 쓰는 사람에게만 필요한 글(단계가 무슨 뜻인지, 왜 이렇게 되는지)을 본문에 깔아 두면
 * 매일 쓰는 선생님에게는 매번 읽어 넘겨야 하는 군더더기가 된다. 필요할 때만 꺼낸다.
 *
 * 안의 내용은 설명이지 조작이 아니다 — 여기에 단추를 넣지 않는다.
 */
export function HelpTip({
  label,
  children,
  align = "right",
}: {
  /** 무엇에 대한 설명인지. 읽기 프로그램이 이 말로 단추를 읽는다 */
  label: string;
  children: React.ReactNode;
  align?: "right" | "left";
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLSpanElement>(null);
  const id = useId();

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <span ref={box} className="relative inline-flex shrink-0">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen((v) => !v)}
        className={`flex size-8 items-center justify-center rounded-full transition-colors ${
          open ? "bg-surface2 text-ink" : "text-line2 hover:bg-surface2 hover:text-ink2"
        }`}
      >
        <span className="sr-only">{label} 설명 {open ? "닫기" : "보기"}</span>
        <HelpCircle aria-hidden size={19} strokeWidth={1.9} />
      </button>

      {open ? (
        <span
          id={id}
          role="note"
          className={`absolute top-[calc(100%+8px)] z-40 block w-[min(26rem,calc(100vw-3rem))] rounded-[18px] border border-line bg-surface p-5 text-left shadow-[0_12px_34px_rgba(120,90,40,0.18)] ${
            align === "right" ? "right-0" : "left-0"
          }`}
        >
          <span className="mb-2 flex items-start justify-between gap-3">
            <b className="text-[15px] font-extrabold text-ink">{label}</b>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="-mt-1 -mr-1 flex size-7 shrink-0 items-center justify-center rounded-full text-muted hover:bg-surface2 hover:text-ink"
            >
              <span className="sr-only">닫기</span>
              <X aria-hidden size={15} strokeWidth={2.2} />
            </button>
          </span>
          <span className="block text-[14px] leading-6 text-ink2">{children}</span>
        </span>
      ) : null}
    </span>
  );
}
