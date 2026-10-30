import { useState } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { InviteCodeCard } from "@/components/org/InviteCodeCard";
import { NotReadyState } from "@/components/org/NotReady";
import {
  Card,
  ConsentStatus,
  EmptyState,
  InstitutionChip,
  Note,
  PageHeader,
} from "@/components/ui";
import {
  ApiError,
  cancelInvitation,
  getChild,
  getChildGuardians,
  getLiveInvitations,
  getTimeline,
  isReady,
  issueInvitation,
  unlinkGuardian,
} from "@/lib/api";
import type { ChildGuardian, ConsentState, LiveInvitation, TimelineEntry } from "@/lib/types";

export async function clientLoader({ params }: { params: { id: string } }) {
  const child = await getChild(params.id);
  if (!child) throw new Response("Not Found", { status: 404 });
  const [timeline, invitations, guardians] = await Promise.all([
    getTimeline(params.id),
    getLiveInvitations(params.id),
    getChildGuardians(params.id),
  ]);
  return { child, timeline, invitations, guardians };
}

export default function ChildDetailPage() {
  const { child, timeline, invitations, guardians } = useLoaderData<typeof clientLoader>();

  return (
    <>
      <PageHeader
        title={`아동 목록 · ${child.name}`}
        description="승인된 요약이 날짜순으로 쌓인 관찰 기록입니다"
        right={
          <Link
            to="/chat"
            className="tap inline-flex items-center rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
          >
            상담 도우미로 이동
          </Link>
        }
      />

      <Card className="mb-6">
        <div className="mb-3 flex flex-wrap items-center gap-2.5">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded-full bg-surface2 text-[16px] font-semibold text-ink2"
          >
            {child.name.slice(0, 1)}
          </span>
          <span className="text-[18px] font-bold">{child.name}</span>
          <span className="text-[14px] text-muted tabular-nums">{child.birthDate} 생</span>
          {child.externalId ? (
            <span className="text-[14px] text-muted tabular-nums">#{child.externalId}</span>
          ) : null}
        </div>
        {/* 상세 API 가 없어 명부에서 찾은 아이는 연결된 기관 정보가 없다 (lib/api.ts getChild) */}
        {child.institutions ? (
          <>
            <p className="mb-2 text-[13px] font-semibold tracking-wider text-muted uppercase">
              연결된 기관
            </p>
            <ul className="flex flex-wrap gap-x-5 gap-y-2">
              {child.institutions.map(({ institution, consent }) => (
                <li key={institution.id} className="flex items-center gap-2 text-[15px]">
                  <InstitutionChip institution={institution} withName />
                  <ConsentStatus state={consent} />
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </Card>

      <GuardianSection childId={child.id} invitations={invitations} guardians={guardians} />

      <h2 className="mb-3 text-[17px] font-bold">기록 타임라인 (승인된 요약)</h2>

      {/* 타임라인 API 가 없는 빌드다. 비어 있다고 하면 "승인된 요약이 없다"로 읽혀 준비 중으로 둔다 */}
      {!isReady("childTimeline") ? (
        <NotReadyState />
      ) : (
        <>
          {/* 동의 철회는 새 처리를 막는다. 우리 기관이 쓴 지난 기록은 그대로 보인다 */}
          {child.consent === "revoked" && timeline.length > 0 ? (
            <div className="mb-4">
              <Note>보호자 동의가 꺼져 새 기록은 처리되지 않아요.</Note>
            </div>
          ) : null}
          {timeline.length === 0 ? (
            <>
              <EmptyState
                icon="◦"
                title="아직 승인된 요약이 없습니다"
                description="기록을 등록하고 1차 검토에서 승인하면 여기에 날짜순으로 쌓입니다. 승인되지 않은 기록은 표시되지 않습니다."
              />
              <div className="mt-4">
                <Note>{emptyNote(child.consent)}</Note>
              </div>
            </>
          ) : (
            <ul className="flex flex-col divide-y divide-line border-y border-line">
              {groupByDate(timeline).map(({ date, entries }) => (
                <li key={date} className="flex gap-5 py-4">
                  <span className="w-14 shrink-0 text-[15px] font-semibold text-ink2 tabular-nums">
                    {date.slice(5).replace("-", ".")}
                  </span>
                  <div className="flex min-w-0 flex-1 flex-col gap-4">
                    {entries.map((entry) => (
                      <div key={entry.id}>
                        {entry.edited ? (
                          <span className="mb-1.5 inline-block rounded bg-humansoft px-1.5 text-[12px] font-semibold text-human">
                            선생님 수정
                          </span>
                        ) : null}
                        <p className="mb-1.5 text-[16px] leading-7 whitespace-pre-line">{entry.content}</p>
                        <p className="text-[13px] text-muted tabular-nums">원본 {entry.sourceCount}건</p>
                      </div>
                    ))}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  );
}

/**
 * 보호자 연결 — 쓸 수 있는 초대 코드(O-15 ~ O-17)와 연결된 보호자(O-18, O-19).
 *
 * 처리 뒤에는 로더를 다시 돌려 서버 목록을 그대로 그린다. 만료된 코드는 서버가 빼고 준다.
 */
function GuardianSection({
  childId,
  invitations,
  guardians,
}: {
  childId: string;
  invitations: LiveInvitation[];
  guardians: ChildGuardian[];
}) {
  const revalidator = useRevalidator();
  const [busy, setBusy] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<ChildGuardian | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(key: string, action: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await action();
      await revalidator.revalidate();
      return true;
    } catch (err) {
      setError(messageOf(err));
      // 이미 처리된 항목이면 목록을 새로 받아 화면을 서버와 맞춘다
      if (err instanceof ApiError && err.status === 404) await revalidator.revalidate();
      return false;
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card className="mb-6">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-[17px] font-bold">보호자 초대 코드</h2>
        <button
          type="button"
          onClick={() => run("issue", () => issueInvitation(childId))}
          disabled={busy !== null}
          className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:opacity-50"
        >
          {busy === "issue" ? "발급 중…" : "새 코드 발급"}
        </button>
      </div>

      {invitations.length === 0 ? (
        <p className="mb-4 text-[15px] text-muted">
          쓸 수 있는 코드가 없습니다. 보호자에게 보낼 코드가 필요하면 새로 발급해 주세요.
        </p>
      ) : (
        <div className="mb-4 flex flex-col gap-3">
          {invitations.map((invitation) => (
            <InviteCodeCard
              key={invitation.id}
              invitation={invitation}
              cancelling={busy === invitation.id}
              onCancel={() => run(invitation.id, () => cancelInvitation(childId, invitation.id))}
            />
          ))}
        </div>
      )}
      <p className="mb-6 text-[14px] leading-6 text-muted">
        코드는 10분 동안 한 번만 쓸 수 있습니다. 여러 개를 발급해 보호자마다 따로 보낼 수 있습니다.
      </p>

      <h2 className="mb-3 text-[17px] font-bold">연결된 보호자</h2>
      {guardians.length === 0 ? (
        <p className="text-[15px] text-muted">아직 코드로 연결한 보호자가 없습니다.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line border-y border-line">
          {guardians.map((g) => (
            <li key={g.userId} className="flex flex-wrap items-center gap-3 py-3">
              <span className="min-w-0 flex-1">
                <span className="block text-[16px] font-semibold">{g.name ?? "이름 없음"}</span>
                <span className="block text-[13px] text-muted tabular-nums">
                  {g.linkedAt.slice(0, 10).replaceAll("-", ".")} 연결
                  {g.linkedByThisOrganization ? "" : " · 다른 기관 코드로 연결"}
                </span>
              </span>
              {g.linkedByThisOrganization ? (
                <button
                  type="button"
                  onClick={() => {
                    setError(null);
                    setConfirming(g);
                  }}
                  disabled={busy !== null}
                  className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-block hover:bg-blocksoft disabled:opacity-50"
                >
                  연결 해제
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {confirming ? (
        <div role="alertdialog" className="mt-4 rounded border border-block/40 bg-blocksoft p-4">
          <p className="mb-1.5 text-[16px] font-bold text-block">
            {confirming.name ?? "이 보호자"}와의 연결을 해제할까요?
          </p>
          <p className="mb-4 text-[15px] leading-7 text-ink2">
            연결은 기관 구분 없이 하나라서,{" "}
            <b className="font-semibold">해제하면 모든 기관에서 이 아이와의 연결이 끊깁니다.</b>{" "}
            잘못된 사람이 코드로 들어왔을 때 쓰세요. 다시 연결하려면 새 코드를 보내야 합니다.
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setConfirming(null)}
              className="tap rounded border border-line2 bg-surface px-4 text-[15px] font-semibold text-ink2"
            >
              취소
            </button>
            <button
              type="button"
              disabled={busy !== null}
              onClick={async () => {
                const ok = await run(`unlink:${confirming.userId}`, () =>
                  unlinkGuardian(childId, confirming.userId),
                );
                if (ok) setConfirming(null);
              }}
              className="tap rounded bg-block px-4 text-[15px] font-semibold text-white disabled:opacity-50"
            >
              {busy === `unlink:${confirming.userId}` ? "해제 중…" : "연결 해제"}
            </button>
          </div>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="mt-4 rounded bg-blocksoft px-3 py-2 text-[14px] text-block">
          {error}
        </p>
      ) : null}
    </Card>
  );
}

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  switch (err.code) {
    case "INVITE_CODE_ISSUE_FAILED":
      return "초대 코드를 만들지 못했어요. 다시 시도해 주세요.";
    case "INVITATION_NOT_FOUND":
      return "이미 쓰였거나 만료된 코드입니다.";
    case "GUARDIAN_NOT_FOUND":
      return "이미 연결이 해제된 보호자입니다.";
    case "GUARDIAN_LINKED_BY_OTHER_ORGANIZATION":
      return "다른 기관 코드로 연결된 보호자는 해제할 수 없습니다.";
    case "CHILD_NOT_FOUND":
      return "우리 기관 명부에 없는 아이입니다.";
  }
  if (err.status === 403) return "보안 확인에 실패했습니다. 새로고침한 뒤 다시 시도해 주세요.";
  return "잠시 후 다시 시도해 주세요.";
}

/**
 * 기록 타임라인을 날짜로 묶는다. 서버 순서(관찰 날짜 최근 순, 같은 날은 승인 최근 순)를 그대로 둔다.
 * 같은 날 승인본이 여럿이면 날짜 하나 아래 여러 장이다 — 따로 승인된 별개의 글이라 합치지 않는다.
 */
function groupByDate(timeline: TimelineEntry[]): { date: string; entries: TimelineEntry[] }[] {
  return timeline.reduce<{ date: string; entries: TimelineEntry[] }[]>((acc, entry) => {
    const last = acc[acc.length - 1];
    if (last && last.date === entry.date) last.entries.push(entry);
    else acc.push({ date: entry.date, entries: [entry] });
    return acc;
  }, []);
}

/** 승인된 요약이 없을 때 — 우리 기관 동의 상태에 따라 다음에 할 일이 다르다 */
function emptyNote(consent: ConsentState): string {
  if (consent === "not_granted") return "보호자 동의가 완료되기 전에는 기록을 올릴 수 없습니다.";
  if (consent === "revoked") return "보호자 동의가 꺼져 새 기록을 올릴 수 없습니다.";
  return "기록 등록 화면에서 첫 기록을 올려보세요.";
}
