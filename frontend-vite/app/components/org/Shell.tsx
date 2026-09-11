import type { LucideIcon } from "lucide-react";
import {
  ClipboardCheck,
  ClipboardPlus,
  History,
  LayoutDashboard,
  Mail,
  MessagesSquare,
  RotateCcw,
  Send,
  Settings,
  Sparkles,
  Users,
} from "lucide-react";
import { Link, useLocation } from "react-router";
import { ME, MY_INSTITUTION } from "@/lib/mock/data";

/**
 * org/layout.tsx 의 clientLoader 가 계산해서 내려주는 원본 카운트.
 * `gate1` 은 이제 사이드바에 노출되는 메뉴가 없어 여기서는 쓰지 않는다 —
 * Gate 1(1차 검토)은 "오늘의 업무" 대시보드 카드에서 그대로 들어갈 수 있고,
 * 데이터/라우트/로직은 손대지 않았다. 필드 자체는 layout.tsx 반환 타입과
 * 맞춰두기 위해 남겨둔다.
 */
export interface OrgShellCounts {
  matching: number;
  reinput: number;
  gate1: number;
  gate2: number;
  inbox: number;
}

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  badge?: number;
}

interface NavSection {
  group: string;
  items: NavItem[];
}

/**
 * 사이드바 메뉴명은 사용자가 "지금 무엇을 해야 하는지" 기준으로 짓는다.
 * 괄호 안은 기존(내부용) 명칭 — 라우트/기능은 전부 그대로다.
 */
function nav(counts: OrgShellCounts): NavSection[] {
  return [
    {
      group: "홈",
      items: [
        { href: "/dashboard", label: "오늘의 업무", icon: LayoutDashboard }, // 처리 현황
      ],
    },
    {
      group: "기록",
      items: [
        { href: "/upload", label: "기록 등록", icon: ClipboardPlus }, // 기록 업로드
        {
          href: "/queue/matching",
          label: "확인이 필요한 기록",
          icon: ClipboardCheck,
          badge: counts.matching,
        }, // 확인 필요 큐
        {
          href: "/queue/reinput",
          label: "수정 요청",
          icon: RotateCcw,
          badge: counts.reinput,
        }, // 재입력 요청 큐
      ],
    },
    {
      group: "아동",
      items: [
        { href: "/children", label: "아동 목록", icon: Users }, // 아동 관리
        { href: "/insights", label: "관찰·분석 결과", icon: Sparkles }, // Insight 목록
      ],
    },
    {
      group: "보호자 소통",
      items: [
        { href: "/gate2", label: "공유할 기록", icon: Send, badge: counts.gate2 }, // Gate 2 발송 검토
        { href: "/inbox", label: "메시지", icon: Mail, badge: counts.inbox }, // 수신함
        { href: "/chat", label: "상담 지원", icon: MessagesSquare }, // 상담 도우미
      ],
    },
    {
      group: "관리",
      items: [
        { href: "/history", label: "업무 기록", icon: History }, // 활동 이력
        { href: "/settings/org", label: "기관 설정", icon: Settings },
      ],
    },
  ];
}

export function OrgShell({
  children,
  counts,
}: {
  children: React.ReactNode;
  counts: OrgShellCounts;
}) {
  const { pathname } = useLocation();
  const NAV = nav(counts);

  return (
    <div className="flex min-h-screen">
      <nav
        aria-label="주 메뉴"
        className="flex w-72 shrink-0 flex-col gap-7 border-r border-line bg-surface px-3 py-5"
      >
        <Link to="/dashboard" className="flex items-center gap-2 px-2">
          <span
            aria-hidden
            className="flex size-8 items-center justify-center rounded bg-accent text-[15px] font-bold text-white"
          >
            잇
          </span>
          <span className="text-[17px] font-bold tracking-tight">잇다 ITDA</span>
        </Link>

        <div className="flex flex-col gap-6">
          {NAV.map((section) => (
            <div key={section.group}>
              <p className="mb-1.5 px-2.5 text-[11px] font-semibold tracking-wider text-muted uppercase">
                {section.group}
              </p>
              <ul className="flex flex-col gap-0.5">
                {section.items.map((item) => {
                  const active =
                    pathname === item.href || pathname.startsWith(item.href + "/");
                  const Icon = item.icon;
                  return (
                    <li key={item.href}>
                      <Link
                        to={item.href}
                        aria-current={active ? "page" : undefined}
                        className={`group flex items-center gap-2.5 rounded-md border-l-[3px] py-2.5 pr-2.5 pl-[9px] text-[14px] leading-none transition-colors duration-200 ease-out ${
                          active
                            ? "border-accent bg-accentsoft font-semibold text-accentink"
                            : "border-transparent text-ink2 hover:bg-surface2 hover:text-ink"
                        }`}
                      >
                        <Icon
                          aria-hidden
                          size={17}
                          strokeWidth={1.75}
                          className="shrink-0 transition-transform duration-200 ease-out group-hover:translate-x-0.5"
                        />
                        <span className="min-w-0 flex-1 truncate">{item.label}</span>
                        {item.badge ? (
                          <span className="shrink-0 rounded-full bg-humansoft px-1.5 py-px text-[11px] font-semibold tabular-nums text-human">
                            {item.badge}
                          </span>
                        ) : null}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>

        <div className="mt-auto flex items-center gap-2.5 border-t border-line px-2.5 pt-4">
          <span
            aria-hidden
            className="flex size-9 items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
          >
            {ME.name.slice(0, 1)}
          </span>
          <span className="min-w-0">
            <span className="block truncate text-[15px] font-semibold">
              {ME.name} {ME.role}
            </span>
            <span className="block truncate text-[13px] text-muted">
              {MY_INSTITUTION.name}
            </span>
          </span>
        </div>
      </nav>

      <main className="min-w-0 flex-1 px-8 py-7">
        <div className="mx-auto max-w-5xl">{children}</div>
      </main>
    </div>
  );
}
