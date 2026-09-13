import { Outlet } from "react-router";
import { ParentShell } from "@/components/parent/Shell";

/** 초대·동의 온보딩까지 포함해 학부모 화면 전체에 공통 셸을 씌운다 — 인증 확인은 하지 않는다. */
export default function ParentShellRoute() {
  return (
    <ParentShell>
      <Outlet />
    </ParentShell>
  );
}
