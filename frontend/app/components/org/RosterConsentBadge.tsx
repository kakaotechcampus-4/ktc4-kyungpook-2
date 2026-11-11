import type { ConsentState } from "@/lib/types";

const LABEL: Record<ConsentState, { text: string; cls: string }> = {
  granted: { text: "이용 중", cls: "text-pass bg-passsoft" },
  not_granted: { text: "보호자 동의 대기", cls: "text-review bg-reviewsoft" },
  revoked: { text: "동의 철회됨", cls: "text-block bg-blocksoft" },
};

export function RosterConsentBadge({ consent, className = "" }: { consent: ConsentState; className?: string }) {
  const { text, cls } = LABEL[consent];
  return <span className={`shrink-0 rounded-full px-3 py-1 text-[13px] font-bold ${cls} ${className}`}>{text}</span>;
}
