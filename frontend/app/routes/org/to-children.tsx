import { redirect, type ClientLoaderFunctionArgs } from "react-router";

/**
 * 옛 주소 → 아동 목록 (북마크·알림 링크 보호).
 * `/gate2` → 분석 승인 칸, `/insights`·`/inbox`·`/inbox/:id` → 아이 칸,
 * `/children/new` → 등록 팝업, `/children/:id` → 그 아이 팝업.
 */
export function clientLoader({ request, params }: ClientLoaderFunctionArgs) {
  const path = new URL(request.url).pathname;
  if (path.startsWith("/gate2")) return redirect("/children?tab=approve");
  if (path === "/children/new") return redirect("/children?register=1");
  if (path.startsWith("/children/") && params.id) return redirect(`/children?child=${encodeURIComponent(params.id)}`);
  return redirect("/children");
}

export default function ToChildren() {
  return null;
}
