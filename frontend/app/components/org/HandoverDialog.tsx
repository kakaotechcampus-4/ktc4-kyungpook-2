import { useState } from "react";
import { Busy } from "@/components/ui";
import { ListPopup } from "@/components/ui/ListPopup";
import { cancelHandover, issueHandover, type IssuedHandover } from "@/lib/api";
import { ApiError } from "@/lib/apiError";
import { formatInviteCode } from "@/lib/invite";

/**
 * 담당자 넘기기(#478) — 후임에게 건넬 인계 코드를 받는다.
 *
 * 후임이 카카오로 로그인해 회원가입에서 이 코드를 넣으면 같은 기관의 담당자가 되고, 이 계정은 탈퇴로 정리된다.
 * 열 때 코드를 바로 만들지 않는다 — 다시 받으면 이전 코드가 취소되므로, 메뉴를 실수로 연 것만으로 이미 보낸 코드가
 * 못 쓰게 되면 안 된다.
 */
export function HandoverDialog({ onClose }: { onClose: () => void }) {
  const [issued, setIssued] = useState<IssuedHandover | null>(null);
  const [busy, setBusy] = useState<"issue" | "cancel" | null>(null);
  const [copied, setCopied] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function issue() {
    setBusy("issue");
    setMessage(null);
    try {
      setIssued(await issueHandover());
      setCopied(false);
    } catch (e) {
      setMessage(messageFor(e));
    } finally {
      setBusy(null);
    }
  }

  async function cancel() {
    setBusy("cancel");
    setMessage(null);
    try {
      await cancelHandover();
      setIssued(null);
      setMessage("코드를 취소했어요. 이제 그 코드로는 가입할 수 없어요.");
    } catch (e) {
      setMessage(messageFor(e));
    } finally {
      setBusy(null);
    }
  }

  async function copy() {
    if (!issued) return;
    try {
      await navigator.clipboard.writeText(formatInviteCode(issued.code));
      setCopied(true);
    } catch {
      // 클립보드 권한이 없으면 화면의 코드를 직접 읽어 전달하면 된다
    }
  }

  const ghost =
    "tap rounded border border-line2 bg-surface px-4 text-[15px] font-semibold text-ink2 disabled:opacity-50";

  return (
    <ListPopup
      title="담당자 넘기기"
      subtitle="후임 담당자에게 기관을 넘겨요"
      busy={busy !== null}
      onClose={onClose}
      footer={
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" onClick={onClose} disabled={busy !== null} className={ghost}>
            닫기
          </button>
          <button
            type="button"
            onClick={issue}
            disabled={busy !== null}
            className="tap rounded bg-ink px-4 text-[15px] font-semibold text-paper disabled:opacity-50"
          >
            {busy === "issue" ? <Busy>만드는 중…</Busy> : issued ? "새 코드 받기" : "인계 코드 받기"}
          </button>
        </div>
      }
    >
      <div className="flex flex-col gap-4 text-[15px] leading-7 text-ink2">
        <ol className="list-decimal space-y-1 pl-5">
          <li>인계 코드를 받아 후임 담당자에게 전해 주세요.</li>
          <li>후임이 카카오로 로그인한 뒤 회원가입에서 [인계 코드로 가입]을 고르고 코드를 넣어요.</li>
          <li>
            그 순간 <b className="text-ink">이 계정은 탈퇴</b>되고, 아동 명부와 기록은 기관에 그대로 남아 후임이 이어서 봐요.
          </li>
        </ol>

        {issued ? (
          <div className="rounded border border-accent/40 bg-accentsoft p-4">
            <p className="font-mono text-2xl font-bold tracking-[0.2em] text-accentink">{formatInviteCode(issued.code)}</p>
            <p className="mt-1 text-[14px] text-ink2 tabular-nums">{until(issued.expiresAt)}까지 · 1회용</p>
            <div className="mt-3 flex flex-wrap gap-2">
              <button type="button" onClick={copy} className="tap rounded bg-ink px-4 text-[15px] font-semibold text-paper">
                {copied ? "복사됨" : "코드 복사"}
              </button>
              <button type="button" onClick={cancel} disabled={busy !== null} className={ghost}>
                {busy === "cancel" ? <Busy>취소 중…</Busy> : "코드 취소"}
              </button>
            </div>
          </div>
        ) : null}

        <p className="text-[14px] text-muted">
          코드는 12시간 동안 한 번만 쓸 수 있어요. 새 코드를 받으면 전에 받은 코드는 쓸 수 없어요.
        </p>

        {message ? (
          <p role="status" className="rounded bg-surface2 px-3 py-2 text-[14px] text-ink2">
            {message}
          </p>
        ) : null}
      </div>
    </ListPopup>
  );
}

/** "11월 10일 오후 6:30" */
function until(iso: string): string {
  return new Date(iso).toLocaleString("ko-KR", {
    month: "long",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

function messageFor(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === "WITHDRAWAL_NOT_AVAILABLE") return "지금은 담당자를 넘길 수 없어요.";
    if (e.code === "HANDOVER_CODE_ISSUE_FAILED") return "코드를 만들지 못했어요. 한 번 더 눌러 주세요.";
  }
  return "잠시 후 다시 시도해 주세요.";
}
