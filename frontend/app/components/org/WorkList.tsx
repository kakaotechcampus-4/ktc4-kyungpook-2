import { useEffect } from "react";
import { ChevronLeft } from "lucide-react";
import { Link } from "react-router";
import { TourButton, type TourScreen } from "@/components/Tour";

export function BackHome() {
  return (
    <Link
      to="/dashboard"
      className="-ml-1.5 mb-1.5 inline-flex items-center gap-0.5 rounded-full py-1 pr-2.5 pl-1 text-[14px] font-bold text-muted hover:bg-surface hover:text-ink hover:shadow-[0_2px_14px_rgba(120,90,40,0.12)]"
    >
      <ChevronLeft aria-hidden size={15} strokeWidth={2.4} />
      대시보드
    </Link>
  );
}

export function WorkHead({
  title,
  jobs,
  right,
  tour,
}: {
  title: string;
  jobs?: string[];
  right?: React.ReactNode;
  tour?: TourScreen;
}) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <BackHome />
        <div className="flex items-center gap-2.5">
          <h1 className="text-[clamp(24px,3.4vw,32px)] leading-[1.25] font-extrabold tracking-[-0.03em] text-ink">{title}</h1>
          {tour ? <TourButton screen={tour} /> : null}
        </div>
        {jobs ? (
          <p className="mt-1.5 text-[15px] text-ink2">
            {jobs.length ? (
              <>
                지금 할 일 · <b>{jobs.join(" · ")}</b>
              </>
            ) : (
              "할 일이 없어요"
            )}
          </p>
        ) : null}
      </div>
      {right}
    </header>
  );
}

export function SectionHead({ title, count }: { title: string; count: number }) {
  return (
    <h2 className="flex items-center gap-2 text-[19px] font-extrabold tracking-[-0.02em]">
      {title}
      <span className="rounded-full bg-surface2 px-2 py-px text-[14px] font-bold tabular-nums text-ink2">{count}</span>
    </h2>
  );
}

const CHIP = {
  ai: "bg-accentsoft text-accentink",
  human: "bg-humansoft text-human",
  bad: "bg-blocksoft text-block",
  idle: "bg-surface2 text-ink2",
} as const;

export function Chip({ tone, children }: { tone: keyof typeof CHIP; children: React.ReactNode }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-[13px] font-bold whitespace-nowrap ${CHIP[tone]}`}>
      {children}
    </span>
  );
}

const CARD = "rounded-[18px] bg-surface shadow-[0_2px_14px_rgba(120,90,40,0.08)]";
export const CLICKABLE = "cursor-pointer transition-shadow hover:shadow-[0_4px_20px_rgba(120,90,40,0.16)]";

export function WorkRow({
  who,
  sub,
  text,
  tag,
  action,
  onOpen,
  done,
}: {
  who: React.ReactNode;
  sub: React.ReactNode;
  text: React.ReactNode;
  tag?: React.ReactNode;
  action?: string;
  onOpen: () => void;
  done?: React.ReactNode;
}) {
  if (done) {
    return (
      <article className={`${CARD} grid items-center px-[18px] py-3.5`}>
        <div className="grid min-w-0">
          <b className="flex items-center gap-2 truncate text-[15px] text-accentink">
            <span aria-hidden>✓</span>
            {done}
          </b>
          <span aria-hidden className="text-[13px]">
            &nbsp;
          </span>
        </div>
      </article>
    );
  }
  return (
    <article
      onClick={action ? onOpen : undefined}
      className={`${CARD} grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 px-[18px] py-3.5 sm:grid-cols-[minmax(110px,160px)_minmax(0,1fr)_auto_auto] ${action ? CLICKABLE : ""}`}
    >
      <div className="grid min-w-0">
        <b className="truncate text-[15px]">{who}</b>
        <span className="truncate text-[13px] text-muted">{sub}</span>
      </div>
      <p className="col-span-full row-start-2 line-clamp-2 min-w-0 text-[14px] text-ink2 sm:col-span-1 sm:row-start-auto sm:truncate">
        {text}
      </p>
      <span className={action ? "hidden sm:inline" : "justify-self-end"}>{tag}</span>
      {action ? (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onOpen();
          }}
          className="tap justify-self-end rounded-full bg-ink px-4 text-[14px] font-bold text-paper hover:brightness-110"
        >
          {action}
        </button>
      ) : (
        <span className="hidden sm:block" />
      )}
    </article>
  );
}

export function EmptyLine({ children }: { children: React.ReactNode }) {
  return <p className="rounded-[18px] bg-surface/60 px-5 py-6 text-center text-[15px] text-muted">{children}</p>;
}

export function PopSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h3 className="mb-2 text-[13px] font-extrabold tracking-[0.02em] text-accentink">{title}</h3>
      {children}
    </section>
  );
}

export function PopButton({
  tone = "line",
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { tone?: "dark" | "line" | "text" }) {
  const cls = {
    dark: "bg-ink text-paper hover:brightness-110",
    line: "border border-line2 text-ink2 hover:bg-surface2",
    text: "text-block underline-offset-2 hover:underline",
  }[tone];
  return (
    <button
      type="button"
      {...props}
      className={`tap rounded-full px-5 text-[15px] font-bold disabled:opacity-50 ${cls} ${props.className ?? ""}`}
    />
  );
}

const TOAST_SLOT = ["bottom-6", "bottom-[84px]", "bottom-[144px]", "bottom-[204px]"] as const;

export function Toast({
  message,
  onGone,
  action,
  ms = 5000,
  persist = false,
  slot = 1,
}: {
  message: string | null;
  onGone: () => void;
  action?: { label: string; onClick: () => void };
  ms?: number;
  persist?: boolean;
  slot?: 0 | 1 | 2 | 3;
}) {
  useEffect(() => {
    if (!message || persist) return;
    const t = setTimeout(onGone, ms);
    return () => clearTimeout(t);
  }, [message, onGone, ms, persist]);
  if (!message) return null;
  return (
    <div
      role="status"
      className={`fixed inset-x-4 ${TOAST_SLOT[slot]} z-[70] mx-auto flex w-fit max-w-[560px] items-center gap-3 rounded-full bg-ink py-2.5 pr-2.5 pl-5 text-[15px] font-bold text-paper shadow-[0_12px_32px_rgba(22,28,27,0.25)]`}
    >
      <span className={action ? "" : "pr-2.5"}>{message}</span>
      {action ? (
        <button
          type="button"
          onClick={action.onClick}
          className="rounded-full bg-paper/15 px-3.5 py-1.5 text-[14px] font-bold text-mark hover:bg-paper/25"
        >
          {action.label}
        </button>
      ) : null}
    </div>
  );
}

export function FixedRows({
  size,
  count,
  empty,
  pager,
  children,
}: {
  size: number;
  count: number;
  empty: React.ReactNode;
  pager?: React.ReactNode;
  children?: React.ReactNode;
}) {
  return (
    <div>
      <div className="relative grid gap-2.5">
        {children}
        {Array.from({ length: Math.max(0, size - count) }, (_, i) => (
          <div key={`blank-${i}`} aria-hidden className="invisible">
            <WorkRow who="-" sub="-" text="-" action="-" onOpen={noop} />
          </div>
        ))}
        {count === 0 ? (
          <p className="absolute inset-0 grid place-items-center rounded-[18px] bg-surface/60 px-5 text-center text-[15px] text-muted">
            {empty}
          </p>
        ) : null}
      </div>
      <div className="min-h-12">{pager}</div>
    </div>
  );
}

const noop = () => {};
