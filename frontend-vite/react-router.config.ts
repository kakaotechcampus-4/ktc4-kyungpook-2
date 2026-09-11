import type { Config } from "@react-router/dev/config";

/**
 * SPA 모드 — Node 서버 없이 정적 파일로 빌드한다.
 * 역할 게이트는 proxy.ts(서버 미들웨어) 대신 각 레이아웃 라우트의
 * clientLoader 가 맡는다 (app/routes/org/layout.tsx, app/routes/parent/guard.tsx).
 */
export default {
  ssr: false,
} satisfies Config;
