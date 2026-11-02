import type { ConsentState } from "@/lib/types";

/** 우리 기관에 대한 동의로 고른다. 아이 전체 상태(status)는 다른 기관 동의만 있어도 active 다 */
const LABEL: Record<ConsentState, { text: string; cls: string }> = {
  granted: { text: "이용 중", cls: "text-pass bg-passsoft" },
  not_granted: { text: "보호자 동의 대기", cls: "text-review bg-reviewsoft" },
  revoked: { text: "동의 철회됨", cls: "text-block bg-blocksoft" },
};

/** 아동 목록 · 아동 상세의 우리 기관 동의 배지 — 두 화면이 같은 문구 · 색을 쓴다 */
export function RosterConsentBadge({ consent, className = "" }: { consent: ConsentState; className?: string }) {
  const { text, cls } = LABEL[consent];
  return <span className={`rounded px-2 py-0.5 text-[13px] font-semibold ${cls} ${className}`}>{text}</span>;
}
