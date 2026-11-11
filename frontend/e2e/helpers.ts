import { expect, type Browser, type BrowserContext, type Locator, type Page } from "@playwright/test";

export const INVITE_CODE = /^[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$/;

let seq = 0;

export function uniqueSuffix(): string {
  seq += 1;
  const time = Date.now().toString(36).slice(-6);
  const n = (seq % 36 ** 2).toString(36).padStart(2, "0");
  return `${time}${n}`;
}

function uniqueBusinessNumber(): string {
  return Array.from({ length: 10 }, () => Math.floor(Math.random() * 10)).join("");
}

export async function newContext(browser: Browser): Promise<BrowserContext> {
  const context = await browser.newContext();
  await context.addInitScript(() => {
    try {
      window.localStorage.setItem("itda.tour.dash.seen", "1");
      window.localStorage.setItem("itda.tour.home.seen", "1");
    } catch {
    }
  });
  return context;
}

export async function devLogin(page: Page, account: string): Promise<void> {
  await page.goto(`/api/v1/dev/login?as=${account}`);
}

export async function signUpOrg(page: Page): Promise<string> {
  const suffix = uniqueSuffix();
  const orgName = `E2E 센터 ${suffix}`;

  await devLogin(page, `e2e-org-${suffix}`);
  await expect(page).toHaveURL(/\/signup/);
  await page.getByRole("radio", { name: /기관 담당자/ }).check();
  await page.getByLabel("기관명").fill(orgName);
  await page.getByLabel("사업자등록번호").fill(uniqueBusinessNumber());
  await page.getByRole("button", { name: "가입하기" }).click();

  await expect(page).toHaveURL(/\/org-terms/);
  await page.getByRole("checkbox", { name: "기관 서비스 이용약관 동의" }).check();
  await page.getByRole("checkbox", { name: "담당자 개인정보 수집 동의" }).check();
  await page.getByRole("checkbox", { name: "아동 정보 보호 서약" }).check();
  await page.getByRole("button", { name: "동의하고 계속하기" }).click();

  await expect(page).toHaveURL(/\/dashboard/);
  return orgName;
}

export async function registerChild(
  page: Page,
): Promise<{ childName: string; inviteCode: string; dialog: Locator }> {
  const suffix = uniqueSuffix();
  const childName = `김테스트${suffix.slice(-4)}`;

  await page.goto("/children?register=1");
  const dialog = page.getByRole("dialog", { name: "아이 등록" });
  await dialog.getByLabel("이름").fill(childName);
  await dialog.getByLabel("생년월일").fill("2018-03-14");
  await dialog.getByLabel("관리번호").fill(`E2E-${suffix}`);
  await dialog.getByRole("button", { name: "등록", exact: true }).click();

  const done = page.getByRole("dialog", { name: `${childName} 등록했어요` });
  const code = done.getByText(INVITE_CODE).first();
  await expect(code).toBeVisible();
  return { childName, inviteCode: (await code.innerText()).trim(), dialog: done };
}
