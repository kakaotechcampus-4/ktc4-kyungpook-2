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
  /** 그 아이에게 아직 열어 보지 않은 리포트가 있는지 — 헤더의 아이 전환 점 */
  hasUnreadReport: (childId: string) => boolean;
  /**
   * 그 아이가 명부에 오른 기관 중 아직 아무도 동의하지 않은 곳이 있는지 — 헤더의 아이 전환 점.
   * 서버의 대기 중 권한 요청(G-40)과 같은 기준이다
   */
  hasPendingRequest: (childId: string) => boolean;
}

const ChildContext = createContext<ChildContextValue | null>(null);

export function ChildProvider({
  kids,
  selectedId,
  unreadInsightChildIds = [],
  children,
}: {
  kids: Child[];
  selectedId: string;
  /** 안 본 리포트가 있는 아이 id (보호자 화면 입구가 센다) */
  unreadInsightChildIds?: string[];
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

  const hasUnreadReport = (childId: string) => unreadInsightChildIds.includes(childId);
  // not_granted 만 본다. 동의했다가 끈 기관(revoked)은 보호자가 직접 끈 것이라 다시 알리지 않고, 수락을 기다리는
  // 기관 연결 요청은 아직 명부에 없어 동의할 수 없다(linkRequests 로 따로 온다) — 둘 다 G-40 에도 없다
  const hasPendingRequest = (childId: string) =>
    kids.find((k) => k.id === childId)?.institutions.some((i) => i.consent === "not_granted") ?? false;

  return (
    <ChildContext.Provider value={{ kids, selected, selectChild, hasUnreadReport, hasPendingRequest }}>
      {children}
    </ChildContext.Provider>
  );
}

export function useChildContext() {
  const ctx = useContext(ChildContext);
  if (!ctx) throw new Error("useChildContext must be used within ChildProvider");
  return ctx;
}
