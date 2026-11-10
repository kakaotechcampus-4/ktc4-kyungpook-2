const KEY = "itda_reinput_draft";

export interface ReinputDraft {
  original: string;
  draft: string;
}

type Drafts = Record<string, ReinputDraft>;

function isDraft(v: unknown): v is ReinputDraft {
  if (typeof v !== "object" || v === null) return false;
  const d = v as Record<string, unknown>;
  return typeof d.original === "string" && typeof d.draft === "string";
}

export function readReinputDrafts(): Drafts {
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

export function saveReinputDraft(itemId: string, draft: ReinputDraft | null) {
  const drafts = readReinputDrafts();
  if (draft) drafts[itemId] = draft;
  else delete drafts[itemId];
  write(drafts);
}

export function forgetReinputDrafts(itemIds: string[]) {
  if (itemIds.length === 0) return;
  const drafts = readReinputDrafts();
  for (const id of itemIds) delete drafts[id];
  write(drafts);
}

export function clearReinputDrafts() {
  write({});
}
