import { useEffect } from "react";
import {
  isRouteErrorResponse,
  Links,
  Meta,
  Outlet,
  Scripts,
  ScrollRestoration,
  useLocation,
  useNavigate,
} from "react-router";
import type { Route } from "./+types/root";
import { FullPageLoading, NavigationProgress } from "@/components/ui/Loading";
import { ApiError } from "@/lib/apiError";
import { reloadOnLoginElsewhere, rememberReturnPath } from "@/lib/auth";
import "./app.css";

export function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>잇다 ITDA</title>
        <meta
          name="description"
          content="기관마다 흩어진 장애아동 관찰 기록을 연결하고, 검증된 돌봄 정보를 안전하게 공유하는 통합 돌봄 지원 시스템"
        />
        <Meta />
        <Links />
      </head>
      <body>
        {children}
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

export default function App() {
  useEffect(() => reloadOnLoginElsewhere(), []);
  return (
    <>
      <NavigationProgress />
      <Outlet />
    </>
  );
}

export function HydrateFallback() {
  return <FullPageLoading />;
}

function useSendExpiredToLogin(expired: boolean) {
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    if (!expired) return;
    const parent = location.pathname === "/parent" || location.pathname.startsWith("/parent/");
    const target = parent ? "/parent/invite" : "/login";
    if (location.pathname === target) return;
    rememberReturnPath(parent ? "parent" : "org", new URL(location.pathname + location.search, window.location.origin));
    navigate(target, { replace: true });
  }, [expired, location.pathname, location.search, navigate]);
}

export function ErrorBoundary({ error }: Route.ErrorBoundaryProps) {
  const expired = error instanceof ApiError && error.status === 401;
  useSendExpiredToLogin(expired);
  if (expired) return <FullPageLoading />;

  let message = "문제가 발생했습니다";
  let details = "예기치 않은 오류가 발생했습니다.";

  if (isRouteErrorResponse(error)) {
    message = error.status === 404 ? "페이지를 찾을 수 없습니다" : "오류";
    details =
      error.status === 404
        ? "요청하신 페이지가 존재하지 않습니다."
        : error.statusText || details;
  } else if (import.meta.env.DEV && error instanceof Error) {
    details = error.message;
  }

  return (
    <main className="mx-auto max-w-lg px-4 py-16 text-center">
      <h1 className="text-xl font-bold">{message}</h1>
      <p className="mt-2 text-muted">{details}</p>
    </main>
  );
}
