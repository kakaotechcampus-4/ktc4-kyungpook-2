import { useState } from "react";
import { Busy } from "@/components/ui";
import { formatInviteCode, formatRemaining, inviteLink, useRemainingMs } from "@/lib/invite";
import type { IssuedInvitation } from "@/lib/types";

export function InviteCodeCard({
  invitation,
  onCancel,
  cancelling = false,
}: {
  invitation: IssuedInvitation;
  onCancel?: () => void;
  cancelling?: boolean;
}) {
  const remaining = useRemainingMs(invitation.expiresAt);
  const expired = remaining === 0;
  const [copied, setCopied] = useState<"code" | "link" | null>(null);

  async function copy(kind: "code" | "link") {
    const text = kind === "code" ? formatInviteCode(invitation.code) : inviteLink(invitation.code);
    try {
      await navigator.clipboard.writeText(text);
      setCopied(kind);
    } catch {
    }
  }

  const ghost =
    "tap rounded border border-accent/50 bg-surface px-4 text-[15px] font-semibold text-accentink";

  return (
    <div
      className={`rounded border p-4 ${expired ? "border-line bg-surface2" : "border-accent/40 bg-accentsoft"}`}
    >
      <div className="mb-3 flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <p
          className={`font-mono text-2xl font-bold tracking-[0.2em] ${expired ? "text-muted line-through" : "text-accentink"}`}
        >
          {formatInviteCode(invitation.code)}
        </p>
        <p className={`text-[14px] tabular-nums ${expired ? "text-block" : "text-ink2"}`}>
          {expired ? "만료됨" : `남은 시간 ${formatRemaining(remaining)} · 1회용`}
        </p>
      </div>

      {expired ? (
        <p className="text-[14px] leading-6 text-ink2">
          이 코드는 더 쓸 수 없습니다. 새 코드를 발급해 보내 주세요.
        </p>
      ) : (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => copy("code")}
            className="tap rounded bg-ink px-4 text-[15px] font-semibold text-paper"
          >
            {copied === "code" ? "복사됨" : "코드 복사"}
          </button>
          <button type="button" onClick={() => copy("link")} className={ghost}>
            {copied === "link" ? "복사됨" : "초대 링크 복사"}
          </button>
          {onCancel ? (
            <button
              type="button"
              onClick={onCancel}
              disabled={cancelling}
              className="tap rounded border border-line2 bg-surface px-4 text-[15px] font-semibold text-ink2 disabled:opacity-50"
            >
              {cancelling ? <Busy>취소 중…</Busy> : "코드 취소"}
            </button>
          ) : null}
        </div>
      )}
    </div>
  );
}
