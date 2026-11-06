/**
 * 검증 수정 요청에서 고치던 본문을 로그인이 풀린 동안 맡겨 두는 자리. 요약 검토의 `gate1Draft.ts` 와 같은 규칙이다.
 *
 * 저장이 401 로 실패하면 카드가 여기에 쓰고, 다시 로그인해 이 화면에 오면 같은 검사 결과 · 같은 원래 본문일 때만
 * 되살린다. 일지 원문이라 sessionStorage 다 — 카카오를 다녀오는 동안은 같은 탭이라 남고, 탭을 닫으면 사라진다.
 * 화면에 반영하면 그 칸을 지우고, 로그아웃하면 모두 지운다.
 *
 * 검사 결과 번호마다 한 칸이다. 요약 검토의 "판"은 없다 — 고쳐서 다시 검사한 기록이 또 막히면 새 번호로 뜨므로,
 * 맡길 때의 원래 본문(`original`)을 함께 남겨 그사이 바뀌었는지 본다.
 */
const KEY = "itda_reinput_draft";

export interface ReinputDraft {
  original: string;
  draft: string;
}

type Drafts = Record<string, ReinputDraft>;

/** 저장소는 손으로 고칠 수 있는 곳이다 — 모양이 틀린 칸은 버린다 */
function isDraft(v: unknown): v is ReinputDraft {
  if (typeof v !== "object" || v === null) return false;
  const d = v as Record<string, unknown>;
  return typeof d.original === "string" && typeof d.draft === "string";
}

/** 읽기만 한다. loader 가 부른다 — 탐색이 취소돼 화면이 안 열려도 맡긴 내용이 남아야 한다 */
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
    // 저장소를 못 쓰면 맡기지 못할 뿐이다 — 고치던 본문은 화면에 남아 있다
  }
}

/** 그 검사 결과의 칸을 쓴다. `null` 이면 지운다 */
export function saveReinputDraft(itemId: string, draft: ReinputDraft | null) {
  const drafts = readReinputDrafts();
  if (draft) drafts[itemId] = draft;
  else delete drafts[itemId];
  write(drafts);
}

/** 그 검사 결과들의 칸만 지운다. 여러 번 불려도 결과가 같다 */
export function forgetReinputDrafts(itemIds: string[]) {
  if (itemIds.length === 0) return;
  const drafts = readReinputDrafts();
  for (const id of itemIds) delete drafts[id];
  write(drafts);
}

export function clearReinputDrafts() {
  write({});
}
