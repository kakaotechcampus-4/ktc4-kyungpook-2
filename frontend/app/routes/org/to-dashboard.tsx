import { redirect } from "react-router";

/** 옛 주소(`/upload`, `/upload/:id`) — 기록 올리기는 대시보드로 옮겼다. 북마크·알림 링크를 살린다 */
export function clientLoader() {
  return redirect("/dashboard");
}

export default function ToDashboard() {
  return null;
}
