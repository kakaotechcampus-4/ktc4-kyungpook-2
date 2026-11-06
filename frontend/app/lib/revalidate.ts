import type { ShouldRevalidateFunctionArgs } from "react-router";

/**
 * 같은 화면에서 주소의 `?` 뒤만 바뀌면(칸 바꾸기 · 바로가기 `?open=` 지우기) loader 를 다시 돌리지 않는다.
 * 화면이 부르는 revalidate() 와 다른 화면으로의 이동은 기본대로 다시 돈다.
 */
export function skipSearchOnlyChange({ currentUrl, nextUrl, formMethod, defaultShouldRevalidate }: ShouldRevalidateFunctionArgs) {
  if (!formMethod && currentUrl.pathname === nextUrl.pathname && currentUrl.search !== nextUrl.search) return false;
  return defaultShouldRevalidate;
}
