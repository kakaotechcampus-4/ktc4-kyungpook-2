"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ME, MY_INSTITUTION } from "@/lib/mock/data";

const NAV: { group: string; items: { href: string; label: string; badge?: number }[] }[] = [
  {
    group: "처리",
    items: [
      { href: "/dashboard", label: "처리 현황" },
      { href: "/upload", label: "기록 업로드" },
      { href: "/queue/matching", label: "확인 필요 큐", badge: 3 },
      { href: "/queue/reinput", label: "재입력 요청 큐", badge: 2 },
    ],
  },
  {
    group: "승인",
    items: [
      { href: "/gate1", label: "Gate 1 승인 대기", badge: 2 },
      { href: "/gate2", label: "Gate 2 발송 검토", badge: 1 },
    ],
  },
  {
    group: "아동과 공유",
    items: [
      { href: "/children", label: "아동 관리" },
      { href: "/insights", label: "Insight 목록" },
      { href: "/inbox", label: "수신함", badge: 2 },
      { href: "/chat", label: "상담 도우미" },
    ],
  },
  {
    group: "관리",
    items: [
      { href: "/history", label: "활동 이력" },
      { href: "/settings/org", label: "기관 설정" },
    ],
  },
];

export function OrgShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();

  return (
    <div className="flex min-h-screen">
      <nav
        aria-label="주 메뉴"
        className="flex w-64 shrink-0 flex-col gap-6 border-r border-line bg-surface px-4 py-5"
      >
        <Link href="/dashboard" className="flex items-center gap-2 px-2">
          <span
            aria-hidden
            className="flex size-8 items-center justify-center rounded bg-accent text-[15px] font-bold text-white"
          >
            잇
          </span>
          <span className="text-[17px] font-bold tracking-tight">잇다 ITDA</span>
        </Link>

        <div className="flex flex-col gap-5">
          {NAV.map((section) => (
            <div key={section.group}>
              <p className="mb-1.5 px-2 text-[12px] font-semibold tracking-wider text-muted uppercase">
                {section.group}
              </p>
              <ul className="flex flex-col">
                {section.items.map((item) => {
                  const active =
                    pathname === item.href || pathname.startsWith(item.href + "/");
                  return (
                    <li key={item.href}>
                      <Link
                        href={item.href}
                        aria-current={active ? "page" : undefined}
                        className={`tap flex items-center justify-between rounded px-2 text-[15px] ${
                          active
                            ? "bg-accentsoft font-semibold text-accentink"
                            : "text-ink2 hover:bg-surface2"
                        }`}
                      >
                        <span>{item.label}</span>
                        {item.badge ? (
                          <span className="ml-2 rounded-full bg-human px-1.5 text-[12px] font-semibold text-white tabular-nums">
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

        <div className="mt-auto flex items-center gap-2.5 border-t border-line px-2 pt-4">
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
