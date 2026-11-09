import { expect, test } from "@playwright/test";
import { newContext, signUpOrg } from "./helpers";

/**
 * 업로드 전 확인 창 (#489). 올린 기록은 취소 · 삭제가 없어 [올리기] 를 눌러야만 서버로 보낸다.
 * 이미 올린 파일과 이름이 같으면 경고한다. 업로드는 AI 상태를 보지 않아 AI 없이도 저장된다.
 * 기관을 매번 새로 만들어, 다른 실행이 올린 파일과 이름이 겹치지 않는다.
 */
test("업로드는 확인 창에서 [올리기] 를 눌러야 올라가고, 같은 이름 파일을 다시 고르면 경고한다", async ({ browser }) => {
  const context = await newContext(browser);
  const page = await context.newPage();
  await signUpOrg(page);

  const file = {
    name: "e2e-관찰일지.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("2026-11-03 블록 놀이에서 친구에게 먼저 차례를 양보했다.\n"),
  };
  const picker = page.locator("#upload-input");
  const dialog = page.getByRole("dialog", { name: "파일 1개를 올릴까요?" });

  // 취소하면 아무것도 보내지 않는다
  await picker.setInputFiles(file);
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(file.name)).toBeVisible();
  await expect(dialog.getByText(/같은 이름 파일/)).toHaveCount(0);
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("아직 올린 파일이 없어요")).toBeVisible();

  // [올리기] 를 누르면 올라가 목록에 보인다
  await picker.setInputFiles(file);
  await dialog.getByRole("button", { name: "올리기" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("list", { name: /올린 파일/ }).getByText(file.name)).toBeVisible();

  // 같은 이름을 다시 고르면 경고한다. 막지는 않는다
  await picker.setInputFiles(file);
  await expect(dialog.getByText("같은 이름 파일을 올렸어요", { exact: false })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "올리기" })).toBeEnabled();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();

  await context.close();
});
