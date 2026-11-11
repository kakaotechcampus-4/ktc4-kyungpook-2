import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, FileText, LogOut, TriangleAlert, User, UserRoundCog } from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router";
import { useNavigating } from "@/components/ui/Loading";
import { Busy } from "@/components/ui";
import { HandoverDialog } from "@/components/org/HandoverDialog";
import { Toast } from "@/components/org/WorkList";
import { getAiStatus, getInboxUnreadCount, getPipeline, loadIfReady } from "@/lib/api";
import { signOut } from "@/lib/auth";
import { onNotice } from "@/lib/notice";
import type { Pipeline } from "@/lib/types";
import { usePolling } from "@/lib/usePolling";

export interface OrgShellMe {
  institutionName: string | null;
  withdrawalEnabled: boolean;
}

export interface OrgShellCounts {
  check: number | null;
  review: number | null;
  children: number | null;
  turnTotal: number | null;
  aiDown: boolean;
  approve: number | null;
  unread: number | null;
}

export interface OrgShellLive {
  counts: OrgShellCounts;
  pipeline: Pipeline | null;
}

export async function loadOrgLive(): Promise<OrgShellLive> {
  const [pipeline, unreadCount, ai] = await Promise.all([
    loadIfReady("dashboard", getPipeline).catch(() => null),
    loadIfReady("inbox", getInboxUnreadCount).catch(() => null),
    loadIfReady("dashboard", getAiStatus).catch(() => null),
  ]);
  const t = pipeline?.turn;
  const unread = unreadCount ?? 0;
  return {
    counts: {
      check: t ? t.confirm + t.blocked : null,
      review: t ? t.review : null,
      children: t ? t.approve + t.requests + unread : null,
      turnTotal: t ? t.confirm + t.blocked + t.review + t.approve + t.requests : null,
      aiDown: ai?.available === false,
      approve: t ? t.approve : null,
      unread: unreadCount,
    },
    pipeline,
  };
}

export interface OrgLive {
  pipeline: Pipeline | null;
  aiDown: boolean;
  refresh: () => Promise<void>;
}

const OrgLiveContext = createContext<OrgLive | null>(null);

export function useOrgLive(): OrgLive {
  const live = useContext(OrgLiveContext);
  if (!live) throw new Error("useOrgLive 는 OrgShell 안에서만 쓴다");
  return live;
}

function arrived(prev: OrgShellCounts, next: OrgShellCounts): "approve" | "unread" | null {
  if (prev.approve != null && next.approve != null && next.approve > prev.approve) return "approve";
  if (prev.unread != null && next.unread != null && next.unread > prev.unread) return "unread";
  return null;
}

const POLL_MS = 10_000;

interface NavItem {
  href: string;
  label: string;
  badge?: number | null;
}

function nav(counts: OrgShellCounts): NavItem[] {
  return [
    { href: "/dashboard", label: "대시보드" },
    { href: "/check", label: "기록 확인", badge: counts.check },
    { href: "/gate1", label: "요약 검토", badge: counts.review },
    { href: "/children", label: "아동 목록", badge: counts.children },
    { href: "/checklists", label: "준비물·확인사항" },
  ];
}

function Count({ n }: { n: number }) {
  return (
    <span className="shrink-0 rounded-full bg-mark px-2 py-px text-[13px] font-bold tabular-nums text-markink">
      {n}
    </span>
  );
}

function isHere(href: string, pathname: string): boolean {
  return pathname === href || pathname.startsWith(href + "/");
}

export function OrgShell({
  children,
  me,
  live: loaded,
}: {
  children: React.ReactNode;
  me: OrgShellMe;
  live: OrgShellLive;
}) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const navigating = useNavigating();

  const [live, setLive] = useState(loaded.counts);
  const [pipeline, setPipeline] = useState(loaded.pipeline);
  const generation = useRef(0);
  useEffect(() => {
    generation.current += 1;
    setLive(loaded.counts);
    if (loaded.pipeline) setPipeline(loaded.pipeline);
  }, [loaded]);
  const [arrival, setArrival] = useState<"approve" | "unread" | null>(null);
  const clearArrival = useCallback(() => setArrival(null), []);
  const [notice, setNotice] = useState<string | null>(null);
  const clearNotice = useCallback(() => setNotice(null), []);
  useEffect(() => onNotice(setNotice), []);
  const refresh = useCallback(async () => {
    const started = ++generation.current;
    const next = await loadOrgLive();
    if (started !== generation.current) return;
    if (next.pipeline) setPipeline(next.pipeline);
    setLive((prev) => {
      const kind = arrived(prev, next.counts);
      if (kind) setArrival(kind);
      return next.counts;
    });
  }, []);
  usePolling(refresh, POLL_MS, true, false);
  const orgLive = useMemo(() => ({ pipeline, aiDown: live.aiDown, refresh }), [pipeline, live.aiDown, refresh]);
  const ITEMS = nav(live);

  useEffect(() => {
    const base = "잇다 ITDA";
    document.title = live.turnTotal ? `(${live.turnTotal}) ${base}` : base;
    return () => {
      document.title = base;
    };
  }, [live.turnTotal]);

  return (
    <div className="org-ui min-h-screen">
      <header className="mx-auto flex max-w-[1240px] flex-wrap items-center gap-x-4 gap-y-3 px-6 py-4">
        <Link to="/dashboard" aria-label="잇다 — 대시보드로">
          <img src="/img/logo.png" alt="잇다" width={120} height={44} className="h-11 w-auto" />
        </Link>

        <nav
          data-tour="menu"
          aria-label="주 메뉴"
          className="order-last flex w-full justify-center overflow-x-auto md:order-none md:w-auto md:flex-1"
        >
          <ul className="flex items-center gap-1 rounded-full bg-surface/90 p-1.5 shadow-[0_2px_14px_rgba(120,90,40,0.12)] backdrop-blur-sm">
            {ITEMS.map((item) => {
              const here = isHere(item.href, pathname);
              return (
                <li key={item.href}>
                  <Link
                    to={item.href}
                    aria-current={here ? "page" : undefined}
                    className={`tap flex items-center gap-1.5 rounded-full px-3.5 text-[16px] font-bold whitespace-nowrap transition-colors duration-200 ease-out sm:px-4 ${
                      here ? "bg-ink text-paper" : "text-ink2 hover:bg-surface2"
                    }`}
                  >
                    {item.label}
                    {item.badge ? <Count n={item.badge} /> : null}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>

        <span className="ml-auto flex shrink-0 items-center gap-2">
          <AccountMenu me={me} />
        </span>
      </header>

      {live.aiDown ? (
        <div role="alert" className="mx-auto mb-2 max-w-[1240px] px-6">
          <p className="flex items-start gap-2.5 rounded-[18px] bg-humansoft px-5 py-3.5 text-[15px] leading-6 text-human">
            <TriangleAlert aria-hidden size={18} className="mt-0.5 shrink-0" />
            <span>
              <b>AI 서버를 점검하고 있어요.</b> 올린 기록은 안전하게 보관돼요. AI가 돌아오면 멈춘 곳부터 자동으로 이어서 처리해요.
            </span>
          </p>
        </div>
      ) : null}

      <main
        aria-busy={navigating}
        className={`mx-auto min-w-0 max-w-[1240px] px-6 pt-4 pb-20 transition-opacity duration-200 ${navigating ? "opacity-60" : ""}`}
      >
        <OrgLiveContext.Provider value={orgLive}>{children}</OrgLiveContext.Provider>
      </main>

      <Toast slot={2} message={notice} onGone={clearNotice} ms={8000} />
      <Toast
        slot={3}
        message={arrival ? "새 리포트가 도착했어요" : null}
        onGone={clearArrival}
        action={{
          label: "보기",
          onClick: () => {
            navigate(arrival === "approve" ? "/children?tab=approve" : "/children");
            setArrival(null);
          },
        }}
      />
    </div>
  );
}

function AccountMenu({ me }: { me: OrgShellMe }) {
  const [open, setOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const [handover, setHandover] = useState(false);
  const box = useRef<HTMLSpanElement>(null);
  const navigate = useNavigate();

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

  async function logout() {
    setSigningOut(true);
    await signOut();
    navigate("/login", { replace: true });
  }

  return (
    <span ref={box} className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="tap flex items-center gap-2 rounded-full bg-surface/90 py-1.5 pr-3 pl-1.5 shadow-[0_2px_14px_rgba(120,90,40,0.12)] hover:brightness-[0.98]"
      >
        <span
          aria-hidden
          className="flex size-9 items-center justify-center rounded-full bg-accentsoft text-[15px] font-bold text-accentink"
        >
          {me.institutionName ? me.institutionName.slice(0, 1) : <User size={18} strokeWidth={1.75} />}
        </span>
        <span className="max-w-[16rem] truncate text-[14px] font-semibold whitespace-nowrap text-ink2">
          {me.institutionName ?? "내 기관"}
        </span>
        <ChevronDown
          aria-hidden
          size={15}
          strokeWidth={2.4}
          className={`text-muted transition-transform duration-200 ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open ? (
        <span
          role="menu"
          className="absolute top-[calc(100%+8px)] right-0 z-40 block w-56 rounded-[18px] border border-line bg-surface p-2 shadow-[0_12px_34px_rgba(120,90,40,0.18)]"
        >
          <Link
            to="/terms/org_tos"
            role="menuitem"
            className="tap flex w-full items-center gap-2.5 rounded-[14px] px-3 text-[15px] font-semibold text-ink2 hover:bg-surface2"
          >
            <FileText aria-hidden size={17} strokeWidth={1.9} className="shrink-0" />
            약관
          </Link>
          {me.withdrawalEnabled ? (
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false);
                setHandover(true);
              }}
              className="tap flex w-full items-center gap-2.5 rounded-[14px] px-3 text-[15px] font-semibold text-ink2 hover:bg-surface2"
            >
              <UserRoundCog aria-hidden size={17} strokeWidth={1.9} className="shrink-0" />
              담당자 넘기기
            </button>
          ) : null}
          <button
            type="button"
            role="menuitem"
            onClick={logout}
            disabled={signingOut}
            className="tap flex w-full items-center gap-2.5 rounded-[14px] px-3 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
          >
            <LogOut aria-hidden size={17} strokeWidth={1.9} className="shrink-0" />
            {signingOut ? <Busy>로그아웃 중…</Busy> : "로그아웃"}
          </button>
        </span>
      ) : null}
      {handover ? <HandoverDialog onClose={() => setHandover(false)} /> : null}
    </span>
  );
}
