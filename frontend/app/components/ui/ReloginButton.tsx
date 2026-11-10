import { useNavigate } from "react-router";
import { rememberReturnPath } from "@/lib/auth";

export function ReloginButton({ role, returnTo }: { role: "org" | "parent"; returnTo?: string }) {
  const navigate = useNavigate();
  return (
    <button
      type="button"
      onClick={() => {
        const here = returnTo ?? window.location.pathname + window.location.search;
        rememberReturnPath(role, new URL(here, window.location.href));
        navigate(role === "parent" ? "/parent/invite" : "/login");
      }}
      className="min-h-9 shrink-0 rounded border border-line2 bg-surface px-3.5 text-[14px] font-semibold text-ink2 hover:bg-surface2"
    >
      다시 로그인
    </button>
  );
}
