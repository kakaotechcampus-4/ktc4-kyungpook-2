import { redirect } from "react-router";

/**
 * "지금 보고 있는 아이"를 저장하는 자리. Context(컴포넌트 트리)와 각 라우트의
 * clientLoader(트리 밖, 훅 사용 불가) 양쪽에서 같은 값을 읽어야 해서 localStorage
 * 를 공유 진실 공급원으로 쓴다 — Context 는 이 값을 읽어서 화면에 보여주고
 * 바꾸는 역할만 한다.
 */
const KEY = "itda_selected_child";

export function readSelectedChildId(kids: { id: string }[]): string {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved && kids.some((k) => k.id === saved)) return saved;
  } catch {
    // localStorage 접근 불가(프라이빗 모드 등) — 첫 번째 아이로 대체
  }
  return kids[0]?.id ?? "";
}

/**
 * 아이 목록에서 고른 아이를 꺼낸다 — 아이 정보만 쓰는 화면이 홈(G-12)을 따로 묻지 않게.
 * 목록이 비었을 때만 없다. 그때는 guard 와 같이 초대 코드 화면으로 보낸다
 */
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
    // 저장 실패해도 화면은 정상 동작해야 한다 — 무시
  }
}
