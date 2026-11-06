import { useNavigate } from "react-router";
import { rememberReturnPath } from "@/lib/auth";

/**
 * 쓰기가 401(로그인 풀림)로 실패했을 때 오류 줄 옆에 붙이는 [다시 로그인].
 *
 * 지금 화면(또는 `returnTo`)을 돌아올 곳으로 남기고 로그인 화면으로 간다 — 기관은 `/login`,
 * 보호자는 `/parent/invite`. 저절로 보내지 않는다. 고치던 글은 이 버튼을 누르기 전까지 화면에 남아
 * 다시 읽거나 복사할 수 있다.
 *
 * `type="button"` 은 지우지 않는다 — 준비물 새 요청처럼 오류 줄이 `<form>` 안에 있으면 눌렀을 때 폼이 제출된다.
 */
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
