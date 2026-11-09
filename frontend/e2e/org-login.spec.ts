import { expect, test } from "@playwright/test";
import { newContext, signUpOrg } from "./helpers";

test("기관 담당자가 로그인해 가입하면 대시보드에 기관명이 보인다", async ({ browser }) => {
  const context = await newContext(browser);
  const page = await context.newPage();

  const orgName = await signUpOrg(page);

  // 오른쪽 위 계정 메뉴. 머리글자는 aria-hidden 이라 버튼 이름이 곧 기관명이다
  await expect(page.getByRole("button", { name: orgName })).toBeVisible();
  await context.close();
});
