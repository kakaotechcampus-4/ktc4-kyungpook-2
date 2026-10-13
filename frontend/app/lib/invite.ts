import { useEffect, useState } from "react";

/**
 * 보호자 초대 코드를 화면에 보여줄 때 쓰는 도구. 기관 화면(I-04 · I-09-1)이 함께 쓴다.
 */

/** 서버가 주는 하이픈 없는 8자를 `K7P3-QX9M` 처럼 4-4 로 나눈다 */
export function formatInviteCode(code: string): string {
  return code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}

/** 보호자에게 보낼 링크. 보호자 진입 화면(P-01)이 `?code=` 를 읽어 보관한다 */
export function inviteLink(code: string): string {
  return `${window.location.origin}/parent/invite?code=${encodeURIComponent(code)}`;
}

/**
 * 만료까지 남은 시간(ms). 1초마다 다시 그린다. 0 이면 만료다.
 *
 * 서버가 준 expiresAt(오프셋 포함)과 브라우저 시계로 계산한다. 브라우저 시계가 틀리면
 * 표시가 어긋나지만, 수락 가능 여부는 어차피 서버가 판정한다.
 */
export function useRemainingMs(expiresAt: string): number {
  const deadline = Date.parse(expiresAt);
  const [now, setNow] = useState(() => Date.now());
  const expired = now >= deadline;

  useEffect(() => {
    if (expired) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [expired]);

  return Math.max(0, deadline - now);
}

/** 남은 시간을 `9:05` 형태로 */
export function formatRemaining(ms: number): string {
  const total = Math.ceil(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}
