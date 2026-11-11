export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message?: string,
  ) {
    super(message ?? code);
  }
}

export function isLoginLost(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401;
}

export interface ShownError {
  text: string;
  loginLost: boolean;
}

export function shownError(err: unknown, text: string): ShownError {
  return { text, loginLost: isLoginLost(err) };
}
