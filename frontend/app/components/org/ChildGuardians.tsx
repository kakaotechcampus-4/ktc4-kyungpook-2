import { useCallback, useEffect, useState } from "react";
import { InviteCodeCard } from "@/components/org/InviteCodeCard";
import { PopButton } from "@/components/org/WorkList";
import { Busy, ConfirmDialog, Spinner } from "@/components/ui";
import {
  ApiError,
  cancelInvitation,
  getChildGuardians,
  getLiveInvitations,
  issueInvitation,
  unlinkGuardian,
} from "@/lib/api";
import type { ChildGuardian, LiveInvitation } from "@/lib/types";

/**
 * 아이 팝업 [보호자] 탭 — 연결된 보호자(O-18 · O-19)와 쓸 수 있는 초대 코드(O-15 ~ O-17).
 * 탭을 열 때 불러오고, 처리할 때마다 서버 목록을 다시 받는다. 만료된 코드는 서버가 빼고 준다.
 */
export function ChildGuardians({ childId, childName }: { childId: string; childName: string }) {
  // 보호자 이름은 저장하지 않는다 — 아이 이름으로 부른다
  const guardianLabel = `${childName} 보호자`;
  const [data, setData] = useState<{ guardians: ChildGuardian[]; invitations: LiveInvitation[] } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<ChildGuardian | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [guardians, invitations] = await Promise.all([getChildGuardians(childId), getLiveInvitations(childId)]);
    setData({ guardians, invitations });
  }, [childId]);

  useEffect(() => {
    load().catch(() => setError("보호자 정보를 불러오지 못했어요. 잠시 뒤 다시 열어 주세요."));
  }, [load]);

  async function run(key: string, action: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await action();
      await load();
      return true;
    } catch (err) {
      setError(messageOf(err));
      if (err instanceof ApiError && err.status === 404) await load().catch(() => undefined);
      return false;
    } finally {
      setBusy(null);
    }
  }

  if (!data) {
    return error ? (
      <p role="alert" className="text-[15px] text-block">
        {error}
      </p>
    ) : (
      <p className="flex items-center gap-2 text-[15px] text-muted">
        <Spinner size={16} /> 불러오는 중…
      </p>
    );
  }

  return (
    <>
      <section className="grid gap-2">
        <h3 className="text-[13px] font-extrabold tracking-[0.02em] text-accentink">연결된 보호자</h3>
        {data.guardians.length === 0 ? (
          <p className="text-[15px] text-muted">아직 연결된 보호자가 없어요</p>
        ) : (
          <ul className="grid">
            {data.guardians.map((g) => (
              <li key={g.userId} className="flex flex-wrap items-center gap-3 border-t border-line py-2.5 first:border-t-0">
                <span className="min-w-0 flex-1">
                  <b className="block text-[15px]">{guardianLabel}</b>
                  <span className="block text-[13px] tabular-nums text-muted">
                    {g.linkedAt.slice(0, 10).replaceAll("-", ".")} 연결
                    {g.linkedByThisOrganization ? "" : " · 다른 기관 코드로 연결"}
                  </span>
                </span>
                {g.linkedByThisOrganization ? (
                  <PopButton tone="text" disabled={busy !== null} onClick={() => setConfirming(g)}>
                    연결 해제
                  </PopButton>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="grid gap-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="text-[13px] font-extrabold tracking-[0.02em] text-accentink">보호자 초대 코드</h3>
          <PopButton disabled={busy !== null} onClick={() => run("issue", () => issueInvitation(childId))}>
            {busy === "issue" ? <Busy>발급 중…</Busy> : "새 코드 발급"}
          </PopButton>
        </div>
        {data.invitations.length === 0 ? (
          <p className="text-[15px] text-muted">쓸 수 있는 코드가 없어요</p>
        ) : (
          <div className="grid gap-3">
            {data.invitations.map((inv) => (
              <InviteCodeCard
                key={inv.id}
                invitation={inv}
                cancelling={busy === inv.id}
                onCancel={() => run(inv.id, () => cancelInvitation(childId, inv.id))}
              />
            ))}
          </div>
        )}
      </section>

      {error ? (
        <p role="alert" className="rounded-[12px] bg-blocksoft px-3 py-2 text-[14px] text-block">
          {error}
        </p>
      ) : null}

      {confirming ? (
        <ConfirmDialog
          title={`${guardianLabel}와의 연결을 해제할까요?`}
          warning="해제하면 모든 기관에서 이 아이와의 연결이 끊겨요"
          confirmLabel="연결 해제"
          tone="block"
          busy={busy !== null}
          onCancel={() => setConfirming(null)}
          onConfirm={async () => {
            const ok = await run(`unlink:${confirming.userId}`, () => unlinkGuardian(childId, confirming.userId));
            if (ok) setConfirming(null);
          }}
        >
          잘못된 사람이 코드로 들어왔을 때 쓰세요. 다시 연결하려면 새 코드를 보내야 해요.
        </ConfirmDialog>
      ) : null}
    </>
  );
}

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  switch (err.code) {
    case "INVITE_CODE_ISSUE_FAILED":
      return "초대 코드를 만들지 못했어요. 다시 시도해 주세요.";
    case "INVITATION_NOT_FOUND":
      return "이미 쓰였거나 만료된 코드예요.";
    case "GUARDIAN_NOT_FOUND":
      return "이미 연결이 해제된 보호자예요.";
    case "GUARDIAN_LINKED_BY_OTHER_ORGANIZATION":
      return "우리 기관 코드로 연결하지 않은 보호자는 해제할 수 없어요.";
    case "CHILD_NOT_FOUND":
      return "우리 기관 명부에 없는 아이예요.";
  }
  if (err.status === 403) return "보안 확인에 실패했어요. 새로고침한 뒤 다시 시도해 주세요.";
  return "잠시 후 다시 시도해 주세요.";
}
