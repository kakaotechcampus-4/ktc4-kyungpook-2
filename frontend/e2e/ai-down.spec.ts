import { expect, test } from "@playwright/test";
import { newContext, signUpOrg } from "./helpers";

test("AI 서버가 꺼져 있으면 기관 화면에 AI 점검 중 안내가 보인다", async ({ browser }) => {
  test.setTimeout(120_000);
  const context = await newContext(browser);
  const page = await context.newPage();
  await signUpOrg(page);

  const banner = page.getByRole("alert").filter({ hasText: "AI 서버를 점검하고 있어요." });
  await expect(banner).toBeVisible({ timeout: 75_000 });
  await expect(banner).toContainText("올린 기록은 안전하게 보관돼요");
  await context.close();
});
