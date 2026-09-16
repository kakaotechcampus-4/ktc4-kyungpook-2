import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [tailwindcss(), reactRouter()],
  resolve: {
    tsconfigPaths: true,
  },
  server: {
    port: 5173,
    strictPort: true,
    /**
     * dev 서버가 `/api` 요청을 백엔드로 대신 넘긴다.
     *
     * 브라우저가 보는 주소는 전부 localhost:5173 하나뿐이라 교차 출처가 아니고,
     * 따라서 CORS 가 발생하지 않는다 — 백엔드의 허용 origin 목록에 5173 을
     * 등록할 필요도 없다. 운영에서 nginx 가 `/api/` 를 backend 로 넘기는 것과
     * 같은 구조라, "dev 에선 되는데 배포하면 막히는" 차이도 생기지 않는다.
     *
     * 백엔드를 다른 곳에 띄웠다면 VITE_DEV_API_TARGET 으로 바꾼다.
     * (compose 기준 backend 는 127.0.0.1:8080 에 바인드된다)
     */
    proxy: {
      "/api": {
        target: process.env.VITE_DEV_API_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
