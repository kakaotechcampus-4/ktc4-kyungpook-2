import { expect, test } from "@playwright/test";
import { newContext, signUpOrg, uniqueSuffix } from "./helpers";

test("기관 담당자가 파일을 올리면 세 단계를 거쳐 올린 파일 목록에 보인다", async ({ browser }) => {
  const context = await newContext(browser);
  const page = await context.newPage();
  await signUpOrg(page);
  const fileName = `e2e-${uniqueSuffix()}.txt`;

  const issued = page.waitForResponse(
    (r) => r.url().endsWith("/api/v1/raw-records/upload-url") && r.request().method() === "POST",
  );
  const put = page.waitForResponse(
    (r) => /\/api\/v1\/raw-records\/uploads\/\d+\/content$/.test(r.url()) && r.request().method() === "PUT",
  );
  const completed = page.waitForResponse((r) => /\/api\/v1\/raw-records\/uploads\/\d+\/complete$/.test(r.url()));
  await page.locator("#upload-input").setInputFiles({
    name: fileName,
    mimeType: "text/plain",
    buffer: Buffer.from("2026-11-10\n오후 미술 활동에 끝까지 참여했어요.\n", "utf-8"),
  });
  await page.getByRole("dialog", { name: "파일 1개를 올릴까요?" }).getByRole("button", { name: "올리기" }).click();

  expect((await issued).status()).toBe(201);
  expect((await put).status()).toBe(200);
  expect((await completed).status()).toBe(200);
  await expect(page.getByTitle(fileName)).toBeVisible();
  await expect(page.getByText("잠시 문제가 있어 저장하지 못했어요")).toHaveCount(0);
  await context.close();
});
