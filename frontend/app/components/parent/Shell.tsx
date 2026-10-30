import { Home, LineChart, ListChecks, Settings } from "lucide-react";
import { Link, useLocation, useRouteLoaderData } from "react-router";
import type { clientLoader as guardLoader } from "@/routes/parent/guard";

const TABS = [
  { href: "/parent", label: "홈", icon: Home, match: (p: string) => p === "/parent" },
  {
    href: "/parent/timeline",
    label: "타임라인",
    icon: ListChecks,
    match: (p: string) => p.startsWith("/parent/timeline") || p.startsWith("/parent/journal"),
  },
  {
    href: "/parent/report",
    label: "리포트",
    icon: LineChart,
    match: (p: string) => p.startsWith("/parent/report"),
  },
  {
    href: "/parent/settings",
    label: "설정",
    icon: Settings,
    match: (p: string) =>
      p.startsWith("/parent/settings") ||
      p.startsWith("/parent/consent/manage") ||
      p.startsWith("/parent/notifications") ||
      p.startsWith("/parent/institution-requests") ||
      p.startsWith("/parent/care-info"),
  },
];

/** 온보딩(초대 진입·확인 동의) 중에는 하단 탭을 숨긴다 — 아직 첫 설정이 안 끝났다. */
const ONBOARDING = ["/parent/invite", "/parent/consent"];

/** 학부모 화면은 SMS 링크로 들어오는 모바일 브라우저가 주 환경이다. */
export function ParentShell({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation();
  // 보호자 입구(guard) 안에서만 값이 있다. 온보딩 화면이나 아직 입구를 지나지 않았으면 undefined — 점이 없다
  const guard = useRouteLoaderData<typeof guardLoader>("routes/parent/guard");
  const unreadReport = guard && "hasUnreadInsight" in guard ? guard.hasUnreadInsight : false;
  const isOnboarding = ONBOARDING.includes(pathname);

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] flex-col bg-surface">
      {isOnboarding ? (
        <header className="flex items-center gap-2 border-b border-line px-4 py-3">
          <span
            aria-hidden
            className="flex size-7 items-center justify-center rounded bg-accent text-[13px] font-bold text-white"
          >
            잇
          </span>
          <span className="text-[16px] font-bold tracking-tight">잇다 ITDA</span>
        </header>
      ) : null}

      <main className={`flex-1 px-4 py-5 md:px-6 md:py-6 ${isOnboarding ? "" : "pb-24"}`}>
        {children}
      </main>

      {!isOnboarding ? (
        <nav
          aria-label="주 메뉴"
          className="fixed bottom-0 left-1/2 flex w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] -translate-x-1/2 border-t border-line bg-surface"
        >
          {TABS.map((t) => {
            const active = t.match(pathname);
            const Icon = t.icon;
            return (
              <Link
                key={t.href}
                to={t.href}
                aria-current={active ? "page" : undefined}
                className={`tap flex flex-1 flex-col items-center justify-center gap-0.5 py-2.5 text-[12px] ${
                  active ? "font-bold text-accentink" : "text-muted"
                }`}
              >
                <span className="relative">
                  <Icon size={22} strokeWidth={active ? 2 : 1.75} />
                  {t.href === "/parent/report" && unreadReport ? (
                    <>
                      <span
                        aria-hidden
                        className="absolute -right-1 -top-0.5 size-2.5 rounded-full bg-human ring-2 ring-surface"
                      />
                      <span className="sr-only">안 본 리포트 있음</span>
                    </>
                  ) : null}
                </span>
                {t.label}
              </Link>
            );
          })}
        </nav>
      ) : null}
    </div>
  );
}
