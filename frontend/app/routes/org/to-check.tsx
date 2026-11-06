import { redirect, type ClientLoaderFunctionArgs } from "react-router";

/** 옛 주소(`/queue/matching`, `/queue/reinput`) — 기록 확인 화면의 해당 칸으로 */
export function clientLoader({ request }: ClientLoaderFunctionArgs) {
  const blocked = new URL(request.url).pathname.endsWith("/reinput");
  return redirect(blocked ? "/check?open=blocked" : "/check?open=confirm");
}

export default function ToCheck() {
  return null;
}
