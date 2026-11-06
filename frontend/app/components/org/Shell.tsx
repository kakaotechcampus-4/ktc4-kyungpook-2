import { useEffect, useRef, useState } from "react";
import { ChevronDown, LogOut, User } from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router";
import { useNavigating } from "@/components/ui/Loading";
import { Busy } from "@/components/ui";
import { getInbox, getPipeline, loadIfReady } from "@/lib/api";
import { signOut } from "@/lib/auth";
import { usePolling } from "@/lib/usePolling";

/** 머리띠 오른쪽의 내 정보. null 은 서버가 주지 않은 값 — 그 칸을 비운다 */
export interface OrgShellMe {
  /** 카카오 닉네임. 동의를 거부하면 없다 */
  name: string | null;
  institutionName: string | null;
}

/** 메뉴 배지. null 은 백엔드가 아직 없는 기능 — 배지를 띄우지 않는다 */
export interface OrgShellCounts {
  /** 아이 확인 + 고쳐야 할 기록 */
  check: number | null;
  /** 검토 대기 (요약 실패 포함) */
  review: number | null;
  /** 분석 승인 + 새 분석(안 읽은 수신함) + 보호자 연결 요청 */
  children: number | null;
}

/** 메뉴 배지 숫자를 센다. 진행 현황(O-80)의 선생님 차례와 수신함(O-50) 안 읽은 글 */
export async function loadOrgCounts(): Promise<OrgShellCounts> {
  const [pipeline, inbox] = await Promise.all([
    loadIfReady("dashboard", getPipeline),
    loadIfReady("inbox", getInbox),
  ]);
  const t = pipeline?.turn;
  const unread = inbox?.filter((i) => !i.readAt).length ?? 0;
  return {
    check: t ? t.confirm + t.blocked : null,
    review: t ? t.review : null,
    children: t ? t.approve + t.requests + unread : null,
  };
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
    { href: "/gate1", label: "검토", badge: counts.review },
    { href: "/children", label: "아동 목록", badge: counts.children },
    { href: "/checklists", label: "준비물·확인사항" },
  ];
}

/** "셀 수 있는 내 차례"에 쓰는 노랑 배지 */
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
  counts,
}: {
  children: React.ReactNode;
  me: OrgShellMe;
  counts: OrgShellCounts;
}) {
  const { pathname } = useLocation();
  // 다음 화면을 기다리는 동안 지금 화면을 살짝 흐린다 — 맨 위 막대(NavigationProgress)와 함께
  const navigating = useNavigating();

  // 처음 숫자는 layout 의 clientLoader 가 준다. 화면을 옮기거나 처리 뒤 revalidate 되면 그 값으로 바뀌고,
  // 그 사이에는 10초마다 직접 다시 센다(탭이 숨으면 멈춤)
  const [live, setLive] = useState(counts);
  useEffect(() => setLive(counts), [counts]);
  usePolling(async () => setLive(await loadOrgCounts()), POLL_MS, true, false);
  const ITEMS = nav(live);

  return (
    <div className="org-ui min-h-screen">
      <header className="mx-auto flex max-w-[1240px] flex-wrap items-center gap-x-4 gap-y-3 px-6 py-4">
        <Link to="/dashboard" aria-label="잇다 — 대시보드로">
          <img src="/img/logo.png" alt="잇다" width={120} height={44} className="h-11 w-auto" />
        </Link>

        <nav
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
          {me.institutionName ? (
            <span className="hidden rounded-full bg-surface/90 px-4 py-2 text-[14px] text-ink2 shadow-[0_2px_14px_rgba(120,90,40,0.1)] lg:inline">
              {me.institutionName}
            </span>
          ) : null}
          <AccountMenu me={me} />
        </span>
      </header>

      <main
        aria-busy={navigating}
        className={`mx-auto min-w-0 max-w-[1240px] px-6 pt-4 pb-20 transition-opacity duration-200 ${navigating ? "opacity-60" : ""}`}
      >
        {children}
      </main>
    </div>
  );
}

/**
 * 머리띠 오른쪽 이름 칩 — 누르면 계정 메뉴가 열린다.
 *
 * 전에는 눌러도 아무 일이 없었고, 로그아웃은 화면 어디에도 없었다. 출입증이 httpOnly 쿠키라
 * 프론트가 스스로 지울 수 없어 서버에 부탁해야 한다(`signOut`).
 */
function AccountMenu({ me }: { me: OrgShellMe }) {
  const [open, setOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
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
          {me.name ? me.name.slice(0, 1) : <User size={18} strokeWidth={1.75} />}
        </span>
        <span className="text-[14px] font-semibold whitespace-nowrap text-ink2">
          {me.name ? `${me.name} 선생님` : "이름 없음"}
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
          {me.institutionName ? (
            <span className="block truncate px-3 py-1.5 text-[13px] text-muted">
              {me.institutionName}
            </span>
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
    </span>
  );
}
