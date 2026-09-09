"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  { href: "/parent", label: "홈" },
  { href: "/parent/consent/manage", label: "동의 관리" },
  { href: "/parent/history", label: "활동 이력" },
];

/** 온보딩(초대 진입·확인 동의) 중에는 하단 탭을 숨긴다 — 아직 계정이 활성화되지 않았다. */
const ONBOARDING = ["/parent/invite", "/parent/consent"];

/** 학부모 화면은 SMS 링크로 들어오는 모바일 브라우저가 주 환경이다. */
export function ParentShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const showTabs = !ONBOARDING.includes(pathname);

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-[430px] flex-col bg-surface">
      <header className="flex items-center gap-2 border-b border-line px-4 py-3">
        <span
          aria-hidden
          className="flex size-7 items-center justify-center rounded bg-accent text-[13px] font-bold text-white"
        >
          잇
        </span>
        <span className="text-[16px] font-bold tracking-tight">잇다 ITDA</span>
      </header>

      <main className="flex-1 px-4 py-5 pb-24">{children}</main>

      {showTabs ? (
        <nav
          aria-label="주 메뉴"
          className="fixed bottom-0 left-1/2 flex w-full max-w-[430px] -translate-x-1/2 border-t border-line bg-surface"
        >
          {TABS.map((t) => {
            const active = pathname === t.href;
            return (
              <Link
                key={t.href}
                href={t.href}
                aria-current={active ? "page" : undefined}
                className={`tap flex flex-1 items-center justify-center py-3 text-[15px] ${
                  active ? "font-semibold text-accentink" : "text-muted"
                }`}
              >
                {t.label}
              </Link>
            );
          })}
        </nav>
      ) : null}
    </div>
  );
}
