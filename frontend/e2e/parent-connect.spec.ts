import { expect, test } from "@playwright/test";
import { devLogin, newContext, registerChild, signUpOrg, uniqueSuffix } from "./helpers";

test("보호자가 초대 코드로 연결하고 동의하면 보호자 홈에 그 아이가 보인다", async ({ browser }) => {
  // 기관과 보호자는 창을 나눈다 — 출입증 쿠키가 하나라 한 창에서 로그인하면 앞 회원을 덮어쓴다
  const orgContext = await newContext(browser);
  const orgPage = await orgContext.newPage();
  const orgName = await signUpOrg(orgPage);
  const { childName, inviteCode } = await registerChild(orgPage);

  const parentContext = await newContext(browser);
  const page = await parentContext.newPage();
  await devLogin(page, `e2e-parent-${uniqueSuffix()}`);

  await expect(page).toHaveURL(/\/signup/);
  await page.getByRole("radio", { name: /보호자/ }).check();
  await page.getByRole("button", { name: "가입하기" }).click();

  // 1/3 약관
  await expect(page).toHaveURL(/\/parent\/invite/);
  await page.getByRole("checkbox", { name: "서비스 이용약관 동의" }).check();
  await page.getByRole("checkbox", { name: "개인정보와 민감정보 수집 동의" }).check();
  await page.getByRole("button", { name: "동의하고 계속하기" }).click();

  // 2/3 초대 코드 — 기관 화면에 보인 그대로(하이픈 포함) 넣는다
  await page.getByLabel("초대 코드").fill(inviteCode);
  await page.getByRole("button", { name: "아이와 연결하기" }).click();
  await expect(page.getByText(`${childName} 학생과 연결됐어요`)).toBeVisible();
  await page.getByRole("link", { name: "공유 동의하러 가기" }).click();

  // 3/3 공유 동의
  await expect(page).toHaveURL(/\/parent\/consent/);
  await page.getByRole("checkbox", { name: `${orgName} 공유에 동의합니다` }).check();
  await page.getByRole("button", { name: "동의하고 시작하기" }).click();

  // 보호자 홈
  await expect(page).toHaveURL(/\/parent$/);
  await expect(page.getByRole("heading", { name: "오늘의 기록" })).toBeVisible();
  await expect(page.getByText(childName).first()).toBeVisible();

  await parentContext.close();
  await orgContext.close();
});
