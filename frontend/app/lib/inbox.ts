import type { InboxItem } from "@/lib/types";

/** 수신함(I-12) 아이 필터의 주소 검색어. `/inbox?childId=…` */
export const INBOX_CHILD_PARAM = "childId";

/**
 * 수신함 안에서 아이 필터만 바꿨는지. 그렇다면 수신함 화면과 기관 레이아웃 loader 를 다시 돌리지 않는다 —
 * 이미 받은 목록을 화면에서 거르기만 하면 되는데, 다시 돌면 수신함과 큐 API 를 모두 다시 부른다.
 *
 * 경로가 다른 이동(상세에서 돌아오기)과 주소가 그대로인 `revalidate()`(배지 다시 세기)는 여기 걸리지 않아
 * 지금처럼 다시 불러온다 — 그래야 방금 읽은 글이 새 글 수 · 배지에 반영된다.
 */
export function onlyInboxFilterChanged(currentUrl: URL, nextUrl: URL): boolean {
  if (currentUrl.pathname !== "/inbox" || nextUrl.pathname !== "/inbox") return false;
  const current = new URLSearchParams(currentUrl.search);
  const next = new URLSearchParams(nextUrl.search);
  if (current.get(INBOX_CHILD_PARAM) === next.get(INBOX_CHILD_PARAM)) return false;
  current.delete(INBOX_CHILD_PARAM);
  next.delete(INBOX_CHILD_PARAM);
  return current.toString() === next.toString();
}

export interface InboxChildOption {
  childId: string;
  label: string;
  unread: number;
}

/**
 * 받은 글이 있는 아이만, 이름 → 관리번호(없으면 뒤) → 아이 id 순. 관리번호 필수 전에 등록된 아이는 번호가
 * 없어 "(관리번호 없음)" 으로 적는다 — 고르기 상자는 같은 이름을 가르는 자리라 비워 두지 않는다.
 */
export function inboxChildOptions(items: InboxItem[]): InboxChildOption[] {
  const byChild = new Map<string, { item: InboxItem; unread: number }>();
  for (const item of items) {
    const entry = byChild.get(item.childId) ?? { item, unread: 0 };
    if (!item.readAt) entry.unread += 1;
    byChild.set(item.childId, entry);
  }
  return [...byChild.values()]
    .sort((a, b) => {
      const byName = a.item.childName.localeCompare(b.item.childName, "ko");
      if (byName !== 0) return byName;
      const x = a.item.externalId;
      const y = b.item.externalId;
      if (x !== y) {
        if (x == null) return 1;
        if (y == null) return -1;
        return x.localeCompare(y, "ko", { numeric: true });
      }
      return a.item.childId.localeCompare(b.item.childId, undefined, { numeric: true });
    })
    .map(({ item, unread }) => ({
      childId: item.childId,
      label: `${item.childName} ${item.externalId ? `#${item.externalId}` : "(관리번호 없음)"}`,
      unread,
    }));
}
