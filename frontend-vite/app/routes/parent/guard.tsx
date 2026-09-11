import { Outlet, redirect, useLoaderData } from "react-router";
import { ChildProvider } from "@/components/parent/ChildContext";
import { getSession } from "@/lib/auth";
import { getParentChildren } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

/**
 * proxy.ts 의 PARENT_PREFIX 게이트를 대체한다. /parent/invite, /parent/consent 는
 * 이 guard 밖(parent/shell.tsx 아래 직속)에 있어 로그인 없이 접근 가능하다.
 *
 * 여기서 아이 목록도 함께 읽어서 ChildProvider 로 내려준다 — 아이 전환은
 * localStorage 에 쓰고 revalidate() 하는 방식이라, 이 clientLoader 가
 * 그 값을 매번 다시 읽어오는 지점이 된다.
 */
export async function clientLoader() {
  const { role } = await getSession();
  if (role !== "parent") return redirect("/parent/invite");
  const kids = await getParentChildren();
  // 동의를 마쳐야만 role이 parent가 되므로 이론상 항상 1명 이상이지만, 방어적으로 처리한다.
  if (kids.length === 0) return redirect("/parent/invite");
  const selectedId = readSelectedChildId(kids);
  return { kids, selectedId };
}

export default function ParentGuard() {
  const { kids, selectedId } = useLoaderData<typeof clientLoader>();
  return (
    <ChildProvider kids={kids} selectedId={selectedId}>
      <Outlet />
    </ChildProvider>
  );
}
