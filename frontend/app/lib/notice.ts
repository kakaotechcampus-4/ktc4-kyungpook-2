/**
 * 화면을 떠난 뒤에도 보여야 하는 알림 — 예: 되돌리기를 기다리다 다른 화면으로 옮길 때 보낸 검토 승인이 실패.
 * 그 화면은 이미 사라졌으므로 늘 떠 있는 메뉴(Shell)가 받아 토스트로 띄운다.
 */
const EVENT = "itda:notice";

export function notify(message: string) {
  window.dispatchEvent(new CustomEvent<string>(EVENT, { detail: message }));
}

export function onNotice(handler: (message: string) => void): () => void {
  const listener = (e: Event) => handler((e as CustomEvent<string>).detail);
  window.addEventListener(EVENT, listener);
  return () => window.removeEventListener(EVENT, listener);
}
