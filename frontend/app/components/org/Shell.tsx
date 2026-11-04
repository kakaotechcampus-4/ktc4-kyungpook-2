import type { LucideIcon } from "lucide-react";
import {
  ChevronDown,
  ClipboardCheck,
  ClipboardPlus,
  LayoutDashboard,
  ListChecks,
  Mail,
  PauseCircle,
  RotateCcw,
  Send,
  Sparkles,
  User,
  Users,
} from "lucide-react";
import { Link, useLocation } from "react-router";
import { useNavigating } from "@/components/ui/Loading";

/** 머리띠 오른쪽의 내 정보. null 은 서버가 주지 않은 값 — 그 칸을 비운다 */
export interface OrgShellMe {
  /** 카카오 닉네임. 동의를 거부하면 없다 */
  name: string | null;
  institutionName: string | null;
}

/**
 * org/layout.tsx 의 clientLoader 가 계산해서 내려주는 원본 카운트.
 * null 은 백엔드가 아직 없는 기능 — 배지를 띄우지 않는다
 */
export interface OrgShellCounts {
  matching: number | null;
  reinput: number | null;
  gate1: number | null;
  gate2: number | null;
  inbox: number | null;
  hold: number | null;
}

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  badge?: number | null;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

/**
 * 머리띠 묶음 세 개. 각 묶음에 마우스를 올리거나 키보드 포커스가 들어오면 세부 화면이 펼쳐진다.
 * 묶음 이름을 눌러도 되는데, 그러면 그 묶음의 **첫 화면**으로 간다 — 눌러도 아무 일 없는 자리를 만들지 않는다.
 */
function nav(counts: OrgShellCounts): NavGroup[] {
  return [
    {
      label: "업로드",
      items: [
        { href: "/upload", label: "기록 등록", icon: ClipboardPlus },
        { href: "/dashboard", label: "대시보드", icon: LayoutDashboard },
      ],
    },
    {
      label: "승인 및 결과",
      items: [
        { href: "/queue/matching", label: "매칭 확인", icon: ClipboardCheck, badge: counts.matching },
        { href: "/gate1", label: "요약 검토", icon: ListChecks, badge: counts.gate1 },
        { href: "/gate1?tab=hold", label: "보류함", icon: PauseCircle, badge: counts.hold },
        { href: "/gate2", label: "인사이트 승인", icon: Send, badge: counts.gate2 },
        { href: "/insights", label: "관찰·분석 결과", icon: Sparkles },
      ],
    },
    {
      label: "관리",
      items: [
        { href: "/queue/reinput", label: "검증 수정 요청", icon: RotateCcw, badge: counts.reinput },
        { href: "/inbox", label: "메시지", icon: Mail, badge: counts.inbox },
        { href: "/children", label: "아동 목록", icon: Users },
      ],
    },
  ];
}

/** 캔버스가 "셀 수 있는 내 차례"에 쓰는 노랑 배지 */
function Count({ n }: { n: number }) {
  return (
    <span className="shrink-0 rounded-full bg-mark px-2 py-px text-[13px] font-bold tabular-nums text-markink">
      {n}
    </span>
  );
}

/** `/gate1?tab=hold` 처럼 물음표가 붙은 메뉴는 그 값까지 봐야 지금 자리를 안다 */
function isHere(href: string, pathname: string, search: string): boolean {
  const [path, query] = href.split("?");
  if (pathname !== path && !pathname.startsWith(path + "/")) return false;
  const here = new URLSearchParams(search);
  if (query) return new URLSearchParams(query).get("tab") === here.get("tab");
  return !here.get("tab");
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
  const { pathname, search } = useLocation();
  // 다음 화면을 기다리는 동안 지금 화면을 살짝 흐린다 — 맨 위 막대(NavigationProgress)와 함께
  const navigating = useNavigating();
  const GROUPS = nav(counts);

  return (
    <div className="org-ui min-h-screen">
      <header className="mx-auto flex max-w-[1240px] flex-wrap items-center gap-x-4 gap-y-3 px-6 py-4">
        <Link to="/dashboard" aria-label="잇다 — 대시보드로">
          <img src="/img/logo.png" alt="잇다" width={120} height={44} className="h-11 w-auto" />
        </Link>

        {/* 묶음 셋. 펼침은 CSS 로만 — 마우스를 올려도, 키보드 포커스가 들어와도 같이 열린다 */}
        <nav
          aria-label="주 메뉴"
          className="order-last flex w-full justify-center md:order-none md:w-auto md:flex-1"
        >
          <ul className="flex items-center gap-1 rounded-full bg-surface/90 p-1.5 shadow-[0_2px_14px_rgba(120,90,40,0.12)] backdrop-blur-sm">
            {GROUPS.map((g) => {
              const here = g.items.some((i) => isHere(i.href, pathname, search));
              const total = g.items.reduce((n, i) => n + (i.badge ?? 0), 0);
              return (
                <li key={g.label} className="group/nav relative">
                  <Link
                    to={g.items[0].href}
                    aria-current={here ? "true" : undefined}
                    className={`tap flex items-center gap-1.5 rounded-full px-4 text-[16px] font-bold whitespace-nowrap transition-colors duration-200 ease-out sm:px-5 ${
                      here
                        ? "bg-ink text-paper"
                        : "text-ink2 hover:bg-surface2 group-focus-within/nav:bg-surface2"
                    }`}
                  >
                    {g.label}
                    {total > 0 ? <Count n={total} /> : null}
                    <ChevronDown
                      aria-hidden
                      size={15}
                      strokeWidth={2.4}
                      className="opacity-60 transition-transform duration-200 group-hover/nav:rotate-180 group-focus-within/nav:rotate-180"
                    />
                  </Link>

                  {/* pt-2 가 알약과 펼침판 사이의 다리다 — 이게 없으면 내려오는 동안 닫힌다 */}
                  <div className="invisible absolute top-full left-1/2 z-40 -translate-x-1/2 pt-2 opacity-0 transition-opacity duration-150 group-hover/nav:visible group-hover/nav:opacity-100 group-focus-within/nav:visible group-focus-within/nav:opacity-100">
                    <ul className="w-56 max-w-[calc(100vw-2.5rem)] rounded-[22px] border border-line bg-surface p-2 shadow-[0_12px_34px_rgba(120,90,40,0.18)]">
                      {g.items.map((item) => {
                        const active = isHere(item.href, pathname, search);
                        const Icon = item.icon;
                        return (
                          <li key={item.href}>
                            <Link
                              to={item.href}
                              aria-current={active ? "page" : undefined}
                              className={`tap flex items-center gap-3 rounded-[14px] px-3 text-[15px] transition-colors duration-150 ${
                                active
                                  ? "bg-accentsoft text-accentink"
                                  : "text-ink2 hover:bg-surface2"
                              }`}
                            >
                              <Icon
                                aria-hidden
                                size={18}
                                strokeWidth={1.8}
                                className="shrink-0"
                              />
                              <span className="min-w-0 flex-1 truncate font-bold text-ink">
                                {item.label}
                              </span>
                              {item.badge ? <Count n={item.badge} /> : null}
                            </Link>
                          </li>
                        );
                      })}
                    </ul>
                  </div>
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
          <span className="flex items-center gap-2 rounded-full bg-surface/90 py-1.5 pr-4 pl-1.5 shadow-[0_2px_14px_rgba(120,90,40,0.12)]">
            <span
              aria-hidden
              className="flex size-9 items-center justify-center rounded-full bg-accentsoft text-[15px] font-bold text-accentink"
            >
              {me.name ? me.name.slice(0, 1) : <User size={18} strokeWidth={1.75} />}
            </span>
            <span className="text-[14px] font-semibold whitespace-nowrap text-ink2">
              {me.name ? `${me.name} 선생님` : "이름 없음"}
            </span>
          </span>
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
