import { useEffect } from "react";
import { ChevronLeft } from "lucide-react";
import { Link } from "react-router";
import { TourButton, type TourScreen } from "@/components/Tour";

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
/** 카드 전체를 눌러 여는 줄의 표시 — 손가락 커서와 hover 그림자 */
export const CLICKABLE = "cursor-pointer transition-shadow hover:shadow-[0_4px_20px_rgba(120,90,40,0.16)]";

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
    // 일반 줄과 같은 두 줄 높이로 — 2초 뒤 사라질 때까지 아래 줄이 흔들리지 않게
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
  // 버튼이 있는 줄은 카드 아무 곳이나 눌러도 연다(키보드 사용자는 버튼으로). 버튼은 stopPropagation 으로 두 번 안 열리게.
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

/** 토스트 자리 — 겹치지 않게 아래에서부터 쌓는다. 0: 되돌리기, 1: 화면 알림, 2: 메뉴 알림(lib/notice), 3: 도착 알림 */
const TOAST_SLOT = ["bottom-6", "bottom-[84px]", "bottom-[144px]", "bottom-[204px]"] as const;

/**
 * 화면 아래에 잠깐 뜨는 알림 한 줄. `message` 가 바뀔 때마다 `ms`(기본 5초) 보인다. `persist` 면 스스로 사라지지 않는다.
 * `action` 을 주면 오른쪽에 버튼(예: 되돌리기)을 둔다.
 */
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

/**
 * 한 쪽 건수(`size`)만큼 높이를 늘 차지하는 목록. 건수가 적거나 0건이어도 칸 높이가 그대로라
 * 아래 칸이 위로 당겨 올라오지 않는다. 모자란 만큼 보이지 않는 빈 줄을 채우고, 0건이면 그 위에 안내를 띄운다.
 * 쪽 번호 자리도 늘 비워 둔다(한 쪽뿐이라 숨겨도 높이가 같게).
 */
export function FixedRows({
  size,
  count,
  empty,
  pager,
  children,
}: {
  size: number;
  /** 지금 쪽에 그린 줄 수 */
  count: number;
  /** 0건일 때 안내 */
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
