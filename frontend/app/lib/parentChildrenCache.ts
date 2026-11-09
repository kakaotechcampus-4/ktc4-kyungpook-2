/**
 * 보호자 아이 목록(G-10) 응답을 잠깐 같이 쓴다.
 *
 * 화면을 옮기면 입구(parent/guard.tsx)와 화면 loader 가 거의 동시에 같은 목록을 묻는다. 입구는 /auth/me 를 기다린 뒤에
 * 묻기 때문에, 진행 중인 요청만 같이 쓰면 화면 쪽 요청이 이미 끝나 있어 놓친다. 그래서 요청을 보낸 뒤 REUSE_MS 동안은
 * 같은 응답을 쓴다.
 *
 * 목록에 담긴 값(아이 · 기관별 동의 · 연결 요청 · 돌봄 정보)을 바꾸는 요청 뒤와 로그아웃 · 로그인 풀림 뒤에는
 * forgetParentChildren 으로 버린다. api.ts 와 auth.ts 가 함께 써서 따로 둔다 — auth.ts 가 api.ts 를 부르면 서로를 부르게 된다.
 */
const REUSE_MS = 2000;

let cached: { at: number; promise: Promise<unknown> } | null = null;

export function shareParentChildren<T>(load: () => Promise<T>): Promise<T> {
  if (cached && Date.now() - cached.at < REUSE_MS) return cached.promise as Promise<T>;
  const promise = load();
  cached = { at: Date.now(), promise };
  // 실패는 같이 쓰지 않는다. 그 사이 새로 보낸 요청이 있으면 그것은 지우지 않는다
  promise.catch(() => {
    if (cached?.promise === promise) cached = null;
  });
  return promise;
}

export function forgetParentChildren(): void {
  cached = null;
}
