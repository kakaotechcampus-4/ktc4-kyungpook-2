import { expect, type Browser, type BrowserContext, type Page } from "@playwright/test";

/**
 * 시나리오들이 함께 쓰는 단계. 화면 문구·라벨로 찾는다 — 사람이 보는 그대로 눌러 본다는 게 이 테스트의 목적이다.
 *
 * 백엔드 메모리 DB 는 서버가 떠 있는 동안 남고, 로컬에서는 이미 떠 있는 서버를 다시 쓰기도 한다.
 * 그래서 개발용 로그인 이름 · 사업자등록번호 · 관리번호를 실행마다 새로 짓는다(같은 이름이면 이미 가입한 회원으로 들어간다).
 */

/** 초대 코드 모양. 헷갈리는 글자(I·L·O·0·1)를 뺀 8자를 4-4 로 끊어 보여 준다 (InviteCodeGenerator) */
export const INVITE_CODE = /^[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$/;

let seq = 0;

/**
 * 실행·호출마다 다른 꼬리, 항상 8자. 개발용 로그인 이름 규칙(영문 소문자·숫자·하이픈 1~20자)에 맞춘다 —
 * 가장 긴 'e2e-parent-' 를 붙여도 19자다. 시각(36진수 뒤 6자 ≈ 25일 주기) + 호출 순번(2자).
 */
export function uniqueSuffix(): string {
  seq += 1;
  const time = Date.now().toString(36).slice(-6);
  const n = (seq % 36 ** 2).toString(36).padStart(2, "0");
  return `${time}${n}`;
}

/** 무작위 숫자 10자리. 사업자등록번호는 기관마다 달라야 한다(체크섬은 검사하지 않는다) */
function uniqueBusinessNumber(): string {
  return Array.from({ length: 10 }, () => Math.floor(Math.random() * 10)).join("");
}

/**
 * 첫 방문에 저절로 뜨는 화면 안내(대시보드 · 보호자 홈)는 화면 전체를 덮어 클릭을 막는다.
 * 화면 코드는 건드리지 않고, 이미 본 것으로 표시해 둔 채 시작한다.
 */
export async function newContext(browser: Browser): Promise<BrowserContext> {
  const context = await browser.newContext();
  await context.addInitScript(() => {
    try {
      window.localStorage.setItem("itda.tour.dash.seen", "1");
      window.localStorage.setItem("itda.tour.home.seen", "1");
    } catch {
      // 저장소를 못 쓰면 안내가 뜬다 — 그때는 테스트가 실패해서 알려 준다
    }
  });
  return context;
}

/** 개발용 로그인(GET /api/v1/dev/login). 처음 쓰는 이름이면 가입 전 회원이 생기고 로그인 성공 화면으로 간다 */
export async function devLogin(page: Page, account: string): Promise<void> {
  await page.goto(`/api/v1/dev/login?as=${account}`);
}

/** 새 기관 담당자로 가입하고 기관 약관에 동의해 대시보드까지 간다. 기관명을 돌려준다 */
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

/** 기관 화면에서 아이를 등록하고, 보호자 초대 코드(XXXX-XXXX 그대로)와 아이 이름을 돌려준다 */
export async function registerChild(page: Page): Promise<{ childName: string; inviteCode: string }> {
  const suffix = uniqueSuffix();
  const childName = `김테스트${suffix.slice(-4)}`;

  await page.goto("/children?register=1");
  const dialog = page.getByRole("dialog", { name: "아이 등록" });
  await dialog.getByLabel("이름").fill(childName);
  await dialog.getByLabel("생년월일").fill("2018-03-14");
  await dialog.getByLabel("관리번호").fill(`E2E-${suffix}`);
  await dialog.getByRole("button", { name: "등록", exact: true }).click();

  // 등록되면 같은 창이 초대 코드 화면으로 바뀐다. 페이지 머리의 '우리 기관 코드'도 같은 모양이라 창 안에서만 찾는다
  const done = page.getByRole("dialog", { name: `${childName} 등록했어요` });
  const code = done.getByText(INVITE_CODE);
  await expect(code).toBeVisible();
  return { childName, inviteCode: (await code.innerText()).trim() };
}
