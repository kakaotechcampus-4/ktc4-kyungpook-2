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
