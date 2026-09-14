import { createContext, useContext } from "react";
import { useRevalidator } from "react-router";
import { writeSelectedChildId } from "@/lib/selectedChild";
import type { Child } from "@/lib/types";

/**
 * "지금 선택된 아이"를 트리 전체에 내려준다. 실제 소스는 localStorage
 * (lib/selectedChild.ts) 다 — 이 Context 는 그 값을 편하게 읽고 바꾸는
 * 창구일 뿐이다. 아이를 바꾸면 revalidate() 로 현재 라우트의 clientLoader
 * 를 다시 돌려서 화면 데이터를 새 아이 기준으로 갱신한다.
 */
interface ChildContextValue {
  kids: Child[];
  selected: Child;
  selectChild: (id: string) => void;
}

const ChildContext = createContext<ChildContextValue | null>(null);

export function ChildProvider({
  kids,
  selectedId,
  children,
}: {
  kids: Child[];
  selectedId: string;
  children: React.ReactNode;
}) {
  const revalidator = useRevalidator();
  const selected = kids.find((k) => k.id === selectedId) ?? kids[0];

  const selectChild = (id: string) => {
    if (!selected || id === selected.id) return;
    writeSelectedChildId(id);
    revalidator.revalidate();
  };

  if (!selected) return <>{children}</>;

  return (
    <ChildContext.Provider value={{ kids, selected, selectChild }}>
      {children}
    </ChildContext.Provider>
  );
}

export function useChildContext() {
  const ctx = useContext(ChildContext);
  if (!ctx) throw new Error("useChildContext must be used within ChildProvider");
  return ctx;
}
