import { expect, test } from "@playwright/test";
import { newContext, signUpOrg } from "./helpers";

/**
 * AI 서버가 꺼져 있을 때 기관 화면이 "점검 중"이라고 알려 주는지 (#482, 평가 ⑦ 실패 시 사용자 안내).
 *
 * E2E 는 AI 서버를 띄우지 않는다. 백엔드 워커가 5초마다 AI /health 를 묻고, 실패가 3번 · 15초 넘게 이어지면
 * AI 상태(GET /api/v1/system/ai-status)가 available=false 가 된다(AiAvailability). 화면은 그 상태를 10초마다 다시 묻는다.
 * 그래서 백엔드가 막 떴을 때는 안내가 늦게 뜰 수 있어 넉넉히 기다린다.
 */
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
