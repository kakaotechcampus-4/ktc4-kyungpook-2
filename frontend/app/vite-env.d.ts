/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 데이터(lib/api.ts)를 mock 으로 돌릴지. "false" 일 때만 실제 API 를 부른다. */
  readonly VITE_USE_MOCK?: string;
  /** 로그인(lib/auth.ts)만 따로 실연동하기 위한 플래그. 데이터와 분리돼 있다. */
  readonly VITE_AUTH_MOCK?: string;
  /** 로그인 진입(/oauth2/authorization/kakao)의 오리진. 운영은 비워 둔다. */
  readonly VITE_AUTH_ORIGIN?: string;
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
