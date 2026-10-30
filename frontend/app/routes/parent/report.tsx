import { ParentNotReadyPage } from "@/components/parent/NotReady";

/** 리포트 탭. 받은 인사이트 화면으로 바뀐다 — 그 전까지 "준비 중" */
export default function ReportPageRoute() {
  return <ParentNotReadyPage title="주간 리포트" showChildSwitch />;
}
