import { Outlet } from "react-router";
import { ParentShell } from "@/components/parent/Shell";
import "./parent.css";

export default function ParentShellRoute() {
  return (
    <ParentShell>
      <Outlet />
    </ParentShell>
  );
}
