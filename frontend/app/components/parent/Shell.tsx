import { Home, LineChart, ListChecks, Settings } from "lucide-react";
import { Link, useLocation, useRouteLoaderData } from "react-router";
import { useNavigating } from "@/components/ui/Loading";
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
      p.startsWith("/parent/checklists") ||
      p.startsWith("/parent/care-info"),
  },
];

const ONBOARDING = ["/parent/invite", "/parent/consent"];

export function ParentShell({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation();
  const guard = useRouteLoaderData<typeof guardLoader>("routes/parent/guard");
  const unreadReport =
    guard && "unreadInsightChildIds" in guard && guard.selectedId
      ? guard.unreadInsightChildIds.includes(guard.selectedId)
      : false;
  const isOnboarding = ONBOARDING.includes(pathname);
  const navigating = useNavigating();

  return (
    <div className="parent-ui mx-auto flex min-h-screen w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] flex-col">
      {isOnboarding ? (
        <header className="flex items-center px-4 py-3">
          <img src="/img/logo.png" alt="잇다" width={98} height={36} className="h-9 w-auto" />
        </header>
      ) : null}

      <main
        aria-busy={navigating}
        className={`flex-1 px-4 pt-5 transition-opacity duration-200 md:px-6 md:pt-6 ${isOnboarding ? "pb-5 md:pb-6" : "pb-24"} ${navigating ? "opacity-60" : ""}`}
      >
        {children}
      </main>

      {!isOnboarding ? (
        <nav
          aria-label="주 메뉴"
          data-tour="tabs"
className="fixed bottom-0 left-1/2 flex w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] -translate-x-1/2 border-t border-line bg-surface/92 backdrop-blur-sm"
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
                  active ? "font-bold text-ink" : "text-muted"
                }`}
              >
                <span className="relative">
                  <Icon size={22} strokeWidth={active ? 2 : 1.75} />
                  {t.href === "/parent/report" && unreadReport ? (
                    <>
                      <span
                        aria-hidden
                        className="absolute -right-1 -top-0.5 size-2.5 rounded-full bg-mark ring-2 ring-surface"
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
