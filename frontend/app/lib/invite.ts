import { useEffect, useState } from "react";

export function formatInviteCode(code: string): string {
  return code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}

export function inviteLink(code: string): string {
  return `${window.location.origin}/parent/invite?code=${encodeURIComponent(code)}`;
}

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

export function formatRemaining(ms: number): string {
  const total = Math.ceil(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}
