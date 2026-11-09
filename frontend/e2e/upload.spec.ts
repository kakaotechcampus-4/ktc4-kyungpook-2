import { expect, test } from "@playwright/test";
import { newContext, signUpOrg, uniqueSuffix } from "./helpers";

/**
 * 원본 기록 업로드가 세 단계(주소 받기 → 본문 올리기 → 완료)로 끝까지 가는지 (#451).
 *
 * E2E 백엔드에는 S3 가 없어 업로드 주소가 BE 의 로컬 경로다 — 브라우저가 쿠키 · CSRF 를 붙여 그 경로에 올린다.
 * 운영의 S3 PUT(쿠키 없이 보내기 · 버킷 CORS · 화면 CSP)은 여기서 보지 못한다. 배포 뒤 Network 탭에서 확인한다.
 */
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
  // 고른 뒤 확인 창에서 [올리기] 를 눌러야 보낸다 (#489). 전체 목록과 이름을 맞춰 본 뒤에야 눌린다
  await page.getByRole("dialog", { name: "파일 1개를 올릴까요?" }).getByRole("button", { name: "올리기" }).click();

  expect((await issued).status()).toBe(201);
  expect((await put).status()).toBe(200);
  expect((await completed).status()).toBe(200);
  // 올린 파일 목록에 보이고, 실패 안내는 뜨지 않는다
  await expect(page.getByTitle(fileName)).toBeVisible();
  await expect(page.getByText("잠시 문제가 있어 저장하지 못했어요")).toHaveCount(0);
  await context.close();
});
