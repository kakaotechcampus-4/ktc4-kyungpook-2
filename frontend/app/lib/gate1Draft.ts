const KEY = "itda_gate1_draft";

export interface Gate1Draft {
  version: string;
  lines: { action: "KEEP" | "EDIT" | "DELETE"; text: string }[];
  added: string[];
}

type Drafts = Record<string, Gate1Draft>;

const ACTIONS = new Set(["KEEP", "EDIT", "DELETE"]);

function isDraft(v: unknown): v is Gate1Draft {
  if (typeof v !== "object" || v === null) return false;
  const d = v as Record<string, unknown>;
  return (
    typeof d.version === "string" &&
    Array.isArray(d.lines) &&
    d.lines.every(
      (l) =>
        typeof l === "object" &&
        l !== null &&
        ACTIONS.has((l as Record<string, unknown>).action as string) &&
        typeof (l as Record<string, unknown>).text === "string",
    ) &&
    Array.isArray(d.added) &&
    d.added.every((a) => typeof a === "string")
  );
}

export function readGate1Drafts(): Drafts {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {};
    return Object.fromEntries(Object.entries(parsed).filter(([, d]) => isDraft(d))) as Drafts;
  } catch {
    return {};
  }
}

function write(drafts: Drafts) {
  try {
    if (Object.keys(drafts).length === 0) sessionStorage.removeItem(KEY);
    else sessionStorage.setItem(KEY, JSON.stringify(drafts));
  } catch {
  }
}

export function saveGate1Draft(summaryId: string, draft: Gate1Draft | null) {
  const drafts = readGate1Drafts();
  if (draft) drafts[summaryId] = draft;
  else delete drafts[summaryId];
  write(drafts);
}

export function forgetGate1Drafts(summaryIds: string[]) {
  if (summaryIds.length === 0) return;
  const drafts = readGate1Drafts();
  for (const id of summaryIds) delete drafts[id];
  write(drafts);
}

export function clearGate1Drafts() {
  write({});
}
