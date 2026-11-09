import { defineConfig, devices } from "@playwright/test";

/**
 * 핵심 흐름 E2E (#482). 실제 프론트(빌드 번들) + 실제 백엔드(H2 메모리 DB)에 대고 브라우저로 눌러 본다.
 *
 * `npm run e2e` 하나로 백엔드·프론트를 띄운다. 이미 3000·8080 에 떠 있으면(로컬) 그 서버를 그대로 쓴다 —
 * 그때 백엔드는 개발용 로그인(DEV_LOGIN_ENABLED=true)이 켜져 있고 AI 서버에 닿지 않아야 한다(ai-down 시나리오).
 *
 * 백엔드 설정 파일은 하나도 더하지 않는다. application-local.yml · application-secret.yml 은 gitignore 라 CI 에
 * 없으므로, H2 · 출입증 비밀값 · 카카오 값을 모두 환경변수로 넘긴다. 카카오 값은 기동만 통과하면 되는 더미다
 * (개발용 로그인은 카카오를 부르지 않는다). AI 서버는 띄우지 않는다 — "AI 점검 중" 안내 시나리오가 이걸 쓴다.
 */
const CI = !!process.env.CI;

export default defineConfig({
  testDir: "./e2e",
  // 백엔드 하나를 함께 쓰므로 한 번에 하나씩. 시나리오끼리는 이름을 매번 새로 지어 데이터를 나누지 않는다.
  workers: 1,
  fullyParallel: false,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  timeout: 90_000,
  reporter: CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:3000",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "./gradlew --no-daemon bootRun",
      cwd: "../backend",
      url: "http://localhost:8080/api/health",
      // 처음엔 Gradle 이 컴파일부터 한다
      timeout: 240_000,
      reuseExistingServer: !CI,
      // Spring 로그는 stdout 으로 나온다. CI 에서 기동이 실패하면 원인이 이 로그에만 있으니 남긴다
      stdout: CI ? "pipe" : "ignore",
      stderr: "pipe",
      env: {
        DEV_LOGIN_ENABLED: "true",
        SPRING_DATASOURCE_URL: "jdbc:h2:mem:e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        SPRING_DATASOURCE_USERNAME: "sa",
        SPRING_DATASOURCE_PASSWORD: "",
        SPRING_DATASOURCE_DRIVER_CLASS_NAME: "org.h2.Driver",
        SPRING_JPA_HIBERNATE_DDL_AUTO: "create-drop",
        JWT_SECRET: "e2e-only-secret-not-used-anywhere-else-0123456789",
        KAKAO_CLIENT_ID: "e2e-dummy-client-id",
        KAKAO_CLIENT_SECRET: "e2e-dummy-client-secret",
        // 아무것도 듣지 않는 포트. 실제 AI 기본 포트(8000)로 두면 로컬에 AI 를 띄워 둔 날 ai-down 시나리오가 깨진다
        AI_BASE_URL: "http://127.0.0.1:9",
      },
    },
    {
      // dev 서버가 아니라 배포와 같은 빌드 번들을 띄운다. dev 서버는 처음 보는 화면에서 의존성을 다시 묶으며
      // 페이지를 새로 고쳐 입력이 날아갈 수 있다. preview 는 dev 서버의 /api 프록시(vite.config.ts)를 그대로 쓴다
      command: "npm run build && npx vite preview --port 3000 --strictPort",
      url: "http://localhost:3000",
      timeout: 120_000,
      reuseExistingServer: !CI,
      stdout: CI ? "pipe" : "ignore",
      stderr: "pipe",
      // 번들에 박히는 값이라 빌드 때 넘긴다. frontend/.env 가 mock 을 켜 둬도 프로세스 환경변수가 이긴다 (Vite 규칙)
      env: {
        VITE_AUTH_MOCK: "false",
        VITE_USE_MOCK: "false",
        VITE_API_BASE_URL: "",
      },
    },
  ],
});
