import { expect, test } from "@playwright/test";
import { INVITE_CODE, newContext, registerChild, signUpOrg } from "./helpers";

test("기관이 아이를 등록하면 보호자 초대 코드가 발급된다", async ({ browser }) => {
  const context = await newContext(browser);
  const page = await context.newPage();
  await signUpOrg(page);

  const { inviteCode, dialog } = await registerChild(page);

  expect(inviteCode).toMatch(INVITE_CODE);
  await expect(dialog.getByText(/남은 시간 \d+:\d{2} · 1회용/)).toBeVisible();
  await context.close();
});
