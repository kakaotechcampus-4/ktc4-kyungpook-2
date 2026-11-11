import { defineConfig, devices } from "@playwright/test";

const CI = !!process.env.CI;
const FRONT_PORT = 13000;
const BACK_PORT = 18080;
const FRONT = `http://localhost:${FRONT_PORT}`;
const BACK = `http://localhost:${BACK_PORT}`;

export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  fullyParallel: false,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  timeout: 90_000,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: FRONT,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "./gradlew --no-daemon bootRun",
      cwd: "../backend",
      url: `${BACK}/api/health`,
      timeout: 240_000,
      reuseExistingServer: false,
      stdout: CI ? "pipe" : "ignore",
      stderr: "pipe",
      env: {
        SERVER_PORT: String(BACK_PORT),
        DEV_LOGIN_ENABLED: "true",
        AUTH_SUCCESS_REDIRECT: `${FRONT}/oauth/success`,
        AUTH_FAILURE_REDIRECT: `${FRONT}/login`,
        CORS_ALLOWED_ORIGINS: FRONT,
        SPRING_DATASOURCE_URL: "jdbc:h2:mem:e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        SPRING_DATASOURCE_USERNAME: "sa",
        SPRING_DATASOURCE_PASSWORD: "",
        SPRING_DATASOURCE_DRIVER_CLASS_NAME: "org.h2.Driver",
        SPRING_JPA_HIBERNATE_DDL_AUTO: "create-drop",
        JWT_SECRET: "e2e-only-secret-not-used-anywhere-else-0123456789",
        KAKAO_CLIENT_ID: "e2e-dummy-client-id",
        KAKAO_CLIENT_SECRET: "e2e-dummy-client-secret",
        AI_BASE_URL: "http://127.0.0.1:9",
      },
    },
    {
      command: `npm run build && npx vite preview --port ${FRONT_PORT} --strictPort`,
      url: FRONT,
      timeout: 120_000,
      reuseExistingServer: false,
      stdout: CI ? "pipe" : "ignore",
      stderr: "pipe",
      env: {
        VITE_AUTH_MOCK: "false",
        VITE_USE_MOCK: "false",
        VITE_API_BASE_URL: "",
        VITE_DEV_API_TARGET: BACK,
      },
    },
  ],
});
