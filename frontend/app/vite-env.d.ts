/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 데이터(lib/api.ts)를 mock 으로 돌릴지. "false" 일 때만 실제 API 를 부른다. */
  readonly VITE_USE_MOCK?: string;
  /** 로그인(lib/auth.ts)만 따로 실연동하기 위한 플래그. 데이터와 분리돼 있다. */
  readonly VITE_AUTH_MOCK?: string;
  readonly VITE_API_BASE_URL?: string;
  /** 카카오 개발자 콘솔의 REST API 키. */
  readonly VITE_KAKAO_CLIENT_ID?: string;
  /** 콘솔·BE 의 kakao.redirect-uri 와 글자까지 같아야 한다. */
  readonly VITE_KAKAO_REDIRECT_URI?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
