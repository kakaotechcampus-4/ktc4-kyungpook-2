/**
 * BE 공통 실패 응답 { result, code, message } 를 담는 에러.
 *
 * lib/api.ts 와 lib/auth.ts 가 함께 쓴다. api.ts 가 auth.ts 를 import 하므로
 * 이 클래스가 api.ts 안에 있으면 auth.ts 가 쓸 때 순환 import 가 생겨 따로 뺐다.
 */
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message?: string,
  ) {
    super(message ?? code);
  }
}

/** 로그인이 풀려 실패했는지 — 화면이 오류 줄 옆에 [다시 로그인]을 붙일지 정한다 */
export function isLoginLost(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401;
}

/**
 * 화면에 띄운 오류 한 줄. 문구와 [다시 로그인] 여부를 한 값으로 든다 — 따로 들면 한쪽만 바뀌어
 * 다른 실패 문구 옆에 옛 버튼이 남는다
 */
export interface ShownError {
  text: string;
  loginLost: boolean;
}

export function shownError(err: unknown, text: string): ShownError {
  return { text, loginLost: isLoginLost(err) };
}
