/**
 * 요약 검토에서 고치던 내용을 로그인이 풀린 동안 맡겨 두는 자리.
 *
 * 저장이 401 로 실패하면 검토 카드가 여기에 쓰고, 다시 로그인해 요약 검토에 오면 같은 요약 · 같은 판일 때만
 * 되살린다. 아이에 대한 문장이 들어가므로 sessionStorage 다 — 카카오를 다녀오는 동안은 같은 탭이라 남고,
 * 탭을 닫으면 사라진다. 화면에 반영하면 그 요약의 칸을 지우고, 로그아웃하면 모두 지운다.
 *
 * 요약 번호마다 한 칸이다. `lines` 는 요약 문장과 같은 길이, `added` 는 새로 쓴 문장의 글(빈 칸도 그대로).
 */
const KEY = "itda_gate1_draft";

export interface Gate1Draft {
  version: string;
  lines: { action: "KEEP" | "EDIT" | "DELETE"; text: string }[];
  added: string[];
}

type Drafts = Record<string, Gate1Draft>;

const ACTIONS = new Set(["KEEP", "EDIT", "DELETE"]);

/** 저장소는 손으로 고칠 수 있는 곳이다 — 모양이 틀린 칸은 버린다 */
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

/** 읽기만 한다. loader 가 부른다 — 탐색이 취소돼 화면이 안 열려도 맡긴 내용이 남아야 한다 */
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
    // 저장소를 못 쓰면 맡기지 못할 뿐이다 — 고치던 내용은 화면에 남아 있다
  }
}

/** 그 요약의 칸을 쓴다. `null` 이면 지운다 */
export function saveGate1Draft(summaryId: string, draft: Gate1Draft | null) {
  const drafts = readGate1Drafts();
  if (draft) drafts[summaryId] = draft;
  else delete drafts[summaryId];
  write(drafts);
}

/** 그 요약들의 칸만 지운다. 여러 번 불려도 결과가 같다 */
export function forgetGate1Drafts(summaryIds: string[]) {
  if (summaryIds.length === 0) return;
  const drafts = readGate1Drafts();
  for (const id of summaryIds) delete drafts[id];
  write(drafts);
}

export function clearGate1Drafts() {
  write({});
}
