import { useEffect } from "react";
import { ChevronLeft } from "lucide-react";
import { Link } from "react-router";
import { TourButton, type TourScreen } from "@/components/org/Tour";

/** 대시보드가 아닌 화면의 제목 위 "‹ 대시보드". 화면마다 같은 자리 */
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

/** 화면 머리: ‹ 대시보드 · 제목 · 할 일 한 줄(데이터) */
export function WorkHead({
  title,
  jobs,
  right,
  tour,
}: {
  title: string;
  jobs?: string[];
  right?: React.ReactNode;
  /** 제목 옆 "?" 사용법 */
  tour?: TourScreen;
}) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <BackHome />
        <h1 className="text-[clamp(24px,3.4vw,32px)] leading-[1.25] font-extrabold tracking-[-0.03em] text-ink">
          {title}
          {tour ? <TourButton screen={tour} /> : null}
        </h1>
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

/** 목록 칸 제목 "아이 확인 3" */
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

/**
 * 목록 한 줄 카드: 누구 · 미리보기 · 표시 · 버튼. 누르면 처리 팝업이 뜬다.
 * `done` 이면 ✓ 한 줄로 바뀐다(2초 뒤 목록에서 빠진다 — useWalk).
 */
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
  /** 없으면 버튼 대신 빈칸 — 지금 열 수 없는 건(요약을 다시 만드는 중) */
  action?: string;
  onOpen: () => void;
  done?: React.ReactNode;
}) {
  if (done) {
    return (
      <article className={`${CARD} flex items-center gap-2 px-[18px] py-3.5 text-[15px] font-bold text-accentink`}>
        <span aria-hidden>✓</span>
        {done}
      </article>
    );
  }
  return (
    <article
      className={`${CARD} grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 px-[18px] py-3.5 sm:grid-cols-[minmax(110px,160px)_minmax(0,1fr)_auto_auto]`}
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
          onClick={onOpen}
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

/** 팝업 본문의 소제목 칸 */
export function PopSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h3 className="mb-2 text-[13px] font-extrabold tracking-[0.02em] text-accentink">{title}</h3>
      {children}
    </section>
  );
}

/** 팝업 버튼 */
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

/**
 * 화면 아래에 잠깐 뜨는 알림 한 줄. `message` 가 바뀔 때마다 `ms`(기본 5초) 보인다.
 * `action` 을 주면 오른쪽에 버튼(예: 되돌리기)을 둔다.
 */
export function Toast({
  message,
  onGone,
  action,
  ms = 5000,
}: {
  message: string | null;
  onGone: () => void;
  action?: { label: string; onClick: () => void };
  ms?: number;
}) {
  useEffect(() => {
    if (!message) return;
    const t = setTimeout(onGone, ms);
    return () => clearTimeout(t);
  }, [message, onGone, ms]);
  if (!message) return null;
  return (
    <div
      role="status"
      className="fixed inset-x-4 bottom-6 z-[70] mx-auto flex w-fit max-w-[560px] items-center gap-3 rounded-full bg-ink py-2.5 pr-2.5 pl-5 text-[15px] font-bold text-paper shadow-[0_12px_32px_rgba(22,28,27,0.25)] max-[560px]:bottom-[calc(24px+env(safe-area-inset-bottom,0px))]"
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
