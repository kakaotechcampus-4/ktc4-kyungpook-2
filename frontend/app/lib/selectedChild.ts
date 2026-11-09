import { redirect } from "react-router";

const KEY = "itda_selected_child";

export function readSelectedChildId(kids: { id: string }[]): string {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved && kids.some((k) => k.id === saved)) return saved;
  } catch {
  }
  return kids[0]?.id ?? "";
}

export function selectedChildOrInvite<T extends { id: string }>(kids: T[]): T {
  const id = readSelectedChildId(kids);
  const child = kids.find((k) => k.id === id);
  if (!child) throw redirect("/parent/invite");
  return child;
}

export function writeSelectedChildId(id: string) {
  try {
    localStorage.setItem(KEY, id);
  } catch {
  }
}
