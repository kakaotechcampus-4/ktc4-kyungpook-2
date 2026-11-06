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
import { rememberReturnPath } from "@/lib/auth";
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
  // 화면을 옮기는 동안 맨 위 막대 — 기관 · 보호자 · 로그인 화면이 함께 쓴다
  return (
    <>
      <NavigationProgress />
      <Outlet />
    </>
  );
}

/**
 * 역할 확인(각 레이아웃의 clientLoader)이 끝나기 전까지 보여주는 화면.
 * proxy.ts 가 서버에서 렌더 전에 끊던 것을, SPA에서는 여기서 "아무 화면도
 * 그리지 않은 채" 기다리는 방식으로 대체한다 — 다른 역할의 화면이 잠깐이라도
 * 보이는 일은 없다.
 */
export function HydrateFallback() {
  return <FullPageLoading />;
}

/**
 * 화면 loader 가 401 로 실패하면(출입증 만료 — 세션 확인 레이아웃보다 화면 loader 가 먼저 끝난 경우 등) 오류 화면 대신
 * 주소를 남기고 로그인 화면으로 보낸다. `/parent` 아래면 보호자 진입 화면, 그 밖은 기관 로그인 화면이다.
 * 화면 안에서 저장하다 받은 401 은 loader 오류가 아니라 여기까지 오지 않는다 — 고치던 내용이 화면에 남는다.
 */
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
