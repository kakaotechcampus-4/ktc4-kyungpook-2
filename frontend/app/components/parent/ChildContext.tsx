import { createContext, useContext } from "react";
import { useRevalidator } from "react-router";
import { writeSelectedChildId } from "@/lib/selectedChild";
import type { Child } from "@/lib/types";

interface ChildContextValue {
  kids: Child[];
  selected: Child;
  selectChild: (id: string) => void;
  hasUnreadReport: (childId: string) => boolean;
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
