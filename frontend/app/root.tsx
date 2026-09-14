import {
  isRouteErrorResponse,
  Links,
  Meta,
  Outlet,
  Scripts,
  ScrollRestoration,
} from "react-router";
import type { Route } from "./+types/root";
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
  return <Outlet />;
}

/**
 * 역할 확인(각 레이아웃의 clientLoader)이 끝나기 전까지 보여주는 화면.
 * proxy.ts 가 서버에서 렌더 전에 끊던 것을, SPA에서는 여기서 "아무 화면도
 * 그리지 않은 채" 기다리는 방식으로 대체한다 — 다른 역할의 화면이 잠깐이라도
 * 보이는 일은 없다.
 */
export function HydrateFallback() {
  return (
    <div className="flex min-h-screen items-center justify-center text-[15px] text-muted">
      불러오는 중…
    </div>
  );
}

export function ErrorBoundary({ error }: Route.ErrorBoundaryProps) {
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
