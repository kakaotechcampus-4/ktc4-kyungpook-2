import { useEffect, useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { ChildGuardians } from "@/components/org/ChildGuardians";
import { ChildTimeline } from "@/components/org/ChildTimeline";
import { Chip, PopButton, PopSection } from "@/components/org/WorkList";
import { Busy, ConfirmDialog, EvidenceChip, InstitutionChip, Spinner } from "@/components/ui";
import { ListPopup } from "@/components/ui/ListPopup";
import { ApiError, decideGate2, getInboxItem, removeChildFromRoster } from "@/lib/api";
import type { ChildListItem, InboxDetail, InboxItem, Insight } from "@/lib/types";

export type ChildTab = "analysis" | "records" | "guardian";

type Past = { kind: "approved"; insight: Insight } | { kind: "inbox"; item: InboxItem };

export function guardianChip(consent: ChildListItem["consent"]): string | null {
  if (consent === "not_granted") return "보호자 동의 전";
  if (consent === "revoked") return "동의 철회";
  return null;
}

function whenOf(p: Past): string {
  if (p.kind === "inbox") return p.item.receivedAt;
  return p.insight.evidence.reduce((max, e) => (e.date > max ? e.date : max), "");
}
function periodOf(p: Past): string {
  return p.kind === "inbox" ? p.item.period : p.insight.period;
}

function byWeek(list: Past[]): { period: string; items: Past[] }[] {
  const groups = new Map<string, Past[]>();
  for (const p of [...list].sort((a, b) => (whenOf(b) > whenOf(a) ? 1 : -1))) {
    const key = periodOf(p);
    groups.set(key, [...(groups.get(key) ?? []), p]);
  }
  return [...groups].map(([period, items]) => ({ period, items }));
}

function firstSentence(text: string): string {
  const m = /^(.+?[.!?。])\s/.exec(text);
  return m ? m[1] : text;
}

export function ChildPopup({
  child,
  review,
  approved,
  inbox,
  initialTab = "analysis",
  openUnread = false,
  position,
  onClose,
  onDecided,
  onStale,
  onRead,
  onRemoved,
}: {
  child: ChildListItem;
  review?: Insight;
  approved: Insight[];
  inbox: InboxItem[];
  initialTab?: ChildTab;
  openUnread?: boolean;
  position?: { index: number; total: number };
  onClose: () => void;
  onDecided: (insight: Insight, decision: "approve" | "hold") => void;
  onStale: (message: string) => void;
  onRead: () => void;
  onRemoved?: (child: ChildListItem) => void;
}) {
  const [tab, setTab] = useState<ChildTab>(initialTab);
  const firstUnread = openUnread ? inbox.find((m) => !m.readAt) : undefined;
  const [view, setView] = useState<Past | null>(firstUnread ? { kind: "inbox", item: firstUnread } : null);
  const [confirming, setConfirming] = useState<"approve" | "hold" | null>(null);
  const [removing, setRemoving] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const unread = inbox.filter((m) => !m.readAt).length;
  const chip = guardianChip(child.consent);

  async function decide(decision: "approve" | "hold") {
    if (!review) return;
    setBusy(true);
    setError(null);
    try {
      await decideGate2(review.id, { decision });
      setConfirming(null);
      onDecided(review, decision);
    } catch (err) {
      setConfirming(null);
      if (isStale(err)) onStale(messageOf(err));
      else setError(messageOf(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await removeChildFromRoster(child.id);
      setRemoving(false);
      onRemoved?.(child);
    } catch (err) {
      setRemoving(false);
      if (err instanceof ApiError && err.status === 404) onStale(`${child.name}은(는) 이미 명부에서 빠졌어요. 목록을 새로 불러왔어요.`);
      else setError(removeMessageOf(err));
    } finally {
      setBusy(false);
    }
  }

  const reviewing = tab === "analysis" && !view && review;
  const footer = view ? (
    <>
      <PopButton onClick={() => setView(null)}>
        <span className="inline-flex items-center gap-0.5">
          <ChevronLeft aria-hidden size={15} />
          목록으로
        </span>
      </PopButton>
      <span className="flex-1" />
      <PopButton tone="dark" onClick={onClose}>
        닫기
      </PopButton>
    </>
  ) : (
    <>
      {onRemoved ? (
        <PopButton tone="text" onClick={() => setRemoving(true)} disabled={busy}>
          명부에서 빼기
        </PopButton>
      ) : null}
      <span className="flex-1" />
      <PopButton tone={reviewing ? "line" : "dark"} onClick={onClose} disabled={busy}>
        닫기
      </PopButton>
      {reviewing ? (
        <>
          <span className="text-[13px] text-muted max-[520px]:order-first max-[520px]:basis-full">승인은 되돌릴 수 없어요</span>
          <PopButton onClick={() => setConfirming("hold")} disabled={busy}>
            보류
          </PopButton>
          <PopButton tone="dark" onClick={() => setConfirming("approve")} disabled={busy}>
            {busy && confirming ? <Busy>보내는 중…</Busy> : "승인"}
          </PopButton>
        </>
      ) : null}
      {error ? (
        <span role="alert" className="basis-full text-[14px] font-bold text-block">
          {error}
        </span>
      ) : null}
    </>
  );

  const past: Past[] = [
    ...approved.map((insight) => ({ kind: "approved" as const, insight })),
    ...inbox.map((item) => ({ kind: "inbox" as const, item })),
  ];

  return (
    <>
      <ListPopup
        title={view ? `${child.name} · ${periodOf(view)} 리포트` : child.name}
        subtitle={`${child.birthDate}${chip ? ` · ${chip}` : ""}`}
        icon={<span className="text-[17px] font-bold">{child.name.slice(0, 1)}</span>}
        position={position}
        busy={busy}
        onClose={onClose}
        footer={footer}
      >
        {view ? null : (
          <div role="tablist" aria-label={`${child.name} 보기`} className="-mt-1 flex w-fit rounded-full bg-surface2 p-1">
            {(
              [
                ["analysis", "리포트"],
                ["records", "기록"],
                ["guardian", "보호자"],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={tab === key}
                onClick={() => setTab(key)}
                className={`flex items-center gap-1.5 rounded-full px-4 py-1.5 text-[14px] font-bold ${
                  tab === key ? "bg-surface text-ink shadow-sm" : "text-ink2"
                }`}
              >
                {label}
                {key === "analysis" && (review || unread) ? <span aria-label="새 리포트" className="size-2 rounded-full bg-block" /> : null}
              </button>
            ))}
          </div>
        )}

        {view ? (
          view.kind === "approved" ? (
            <Report insight={view.insight} kind="approved" />
          ) : (
            <InboxReport item={view.item} onRead={onRead} />
          )
        ) : tab === "records" ? (
          <ChildTimeline childId={child.id} />
        ) : tab === "guardian" ? (
          <ChildGuardians childId={child.id} childName={child.name} />
        ) : (
          <>
            {review ? <Report insight={review} kind="review" /> : null}
            {past.length ? (
              <>
                {review ? <hr className="border-line" /> : null}
                <PopSection title={`지난 리포트 ${past.length}건 · 주차별`}>
                  <div className="grid gap-3">
                    {byWeek(past).map((w) => (
                      <div key={w.period}>
                        <h4 className="mb-1 text-[14px] font-extrabold">{w.period}</h4>
                        <ul className="grid">
                          {w.items.map((p) => (
                            <li key={p.kind === "inbox" ? `in-${p.item.id}` : `ap-${p.insight.id}`}>
                              <button
                                type="button"
                                onClick={() => setView(p)}
                                className="flex w-full items-center gap-2 border-t border-line py-2.5 text-left first:border-t-0 hover:bg-surface2/60"
                              >
                                {p.kind === "inbox" ? (
                                  <Chip tone="ai">{p.item.from.name}가 보냄</Chip>
                                ) : (
                                  <Chip tone="idle">우리 기관 승인</Chip>
                                )}
                                <span className="min-w-0 flex-1 truncate text-[14px]">
                                  {p.kind === "inbox" ? `${p.item.period} 리포트` : firstSentence(p.insight.content)}
                                </span>
                                {p.kind === "inbox" && !p.item.readAt ? <Chip tone="human">새 리포트</Chip> : null}
                                <ChevronRight aria-hidden size={16} className="text-muted" />
                              </button>
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                  </div>
                </PopSection>
              </>
            ) : !review ? (
              <p className="text-[15px] text-muted">아직 리포트가 없어요</p>
            ) : null}
          </>
        )}
      </ListPopup>

      {confirming && review ? (
        <ConfirmDialog
          title={`${child.name} 리포트를 ${confirming === "approve" ? "승인할까요?" : "보류할까요?"}`}
          warning="되돌릴 수 없어요"
          confirmLabel={confirming === "approve" ? "승인하기" : "보류하기"}
          tone={confirming === "approve" ? "human" : "neutral"}
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => decide(confirming)}
        >
          {confirming === "approve"
            ? `승인하면 ${viewers(review).join("·")}가 바로 볼 수 있어요.`
            : "보류하면 이 리포트는 보호자와 다른 기관에 보이지 않아요."}
        </ConfirmDialog>
      ) : null}

      {removing ? (
        <ConfirmDialog
          title={`${child.name}을(를) 우리 기관 명부에서 뺄까요?`}
          warning="다시 넣으려면 보호자가 연결 요청을 보내야 해요"
          confirmLabel="명부에서 빼기"
          tone="block"
          busy={busy}
          onCancel={() => setRemoving(false)}
          onConfirm={remove}
        >
          우리 기관에 준 공유 동의가 꺼지고 쓰지 않은 초대 코드도 취소돼요. 받은 리포트도 더 볼 수 없어요. 보호자 연결과
          지난 기록은 그대로 남아요.
        </ConfirmDialog>
      ) : null}
    </>
  );
}

function viewers(insight: Insight): string[] {
  return ["보호자", ...insight.targets.filter((t) => t.consent === "granted").map((t) => t.institution.name)];
}

function Paragraphs({ text }: { text: string }) {
  return (
    <div className="rounded-[14px] border border-line bg-paper px-4 py-3.5">
      {text.split(/\n+/).map((t, i) => (
        <p key={i} className="max-w-[62ch] text-[16px] leading-[1.75] [&+&]:mt-2">
          {t}
        </p>
      ))}
    </div>
  );
}

function Report({ insight, kind }: { insight: Insight; kind: "review" | "approved" }) {
  const granted = insight.targets.filter((t) => t.consent === "granted");
  const lookedUp = insight.evidence.some((e) => e.lookedUp);
  return (
    <>
      <p className="flex flex-wrap items-center gap-2 text-[14px] text-ink2">
        <Chip tone="idle">{insight.period}</Chip>
        {kind === "review" ? `기록 ${insight.evidence.length}건 · 승인 전` : "우리 기관이 승인"}
      </p>
      {lookedUp ? (
        <p className="rounded-[14px] bg-accentsoft px-3.5 py-3 text-[14px] leading-normal text-accentink">
          <b>AI가 지난 기록도 찾아 봤어요.</b> 이번 기간 기록이 아닌 근거는 &lsquo;지난 기록&rsquo;으로 표시했어요. 날짜와 기관을
          확인해 주세요.
        </p>
      ) : null}
      <PopSection title="리포트 내용">
        <Paragraphs text={insight.content} />
      </PopSection>
      <PopSection title={`근거 기록 ${insight.evidence.length}건`}>
        <ul className="flex flex-col items-start gap-2">
          {insight.evidence.map((e) => (
            <li key={e.childContextId} className="flex flex-wrap items-center gap-2">
              <EvidenceChip date={e.date} label={e.label} institution={e.institution} />
              {e.lookedUp ? <Chip tone="ai">지난 기록</Chip> : null}
            </li>
          ))}
        </ul>
      </PopSection>
      <PopSection title={kind === "review" ? "승인하면 볼 수 있는 곳" : "볼 수 있는 곳"}>
        <ul className="grid">
          <li className="border-t border-line py-2 text-[14px] font-bold first:border-t-0">보호자</li>
          {granted.map((t) => (
            <li key={t.institution.id} className="flex items-center gap-2 border-t border-line py-2 text-[14px]">
              <InstitutionChip institution={t.institution} withName />
              <Chip tone="ai">공유 동의함</Chip>
            </li>
          ))}
        </ul>
      </PopSection>
      {insight.teacherWritten ? (
        <div role="alert" className="rounded-[14px] bg-humansoft px-3.5 py-3 text-[14px] leading-normal text-human">
          <b>선생님이 고쳐 쓴 문장이 근거에 들어 있어요.</b> 내용 검증을 거치지 않은 문장이니 내용을 한 번 더 봐 주세요.
        </div>
      ) : null}
    </>
  );
}

function InboxReport({ item, onRead }: { item: InboxItem; onRead: () => void }) {
  const [detail, setDetail] = useState<InboxDetail | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    getInboxItem(item.id).then(
      (d) => {
        if (!alive) return;
        setDetail(d);
        if (!item.readAt) onRead();
      },
      () => alive && setFailed(true),
    );
    return () => {
      alive = false;
    };
  }, [item.id]);

  if (failed) return <p className="text-[15px] text-block">더 이상 볼 수 없는 리포트예요. 보호자가 공유를 껐을 수 있어요.</p>;
  if (!detail) {
    return (
      <p className="flex items-center gap-2 text-[15px] text-muted">
        <Spinner size={16} /> 불러오는 중…
      </p>
    );
  }
  return (
    <>
      <p className="flex flex-wrap items-center gap-2 text-[14px] text-ink2">
        <Chip tone="idle">{detail.period}</Chip>
        {detail.from.name}가 보냄
      </p>
      <PopSection title="리포트 내용">
        <Paragraphs text={detail.content} />
      </PopSection>
    </>
  );
}

function removeMessageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (err.status === 403) return "보안 확인에 실패했어요. 새로고침한 뒤 다시 시도해 주세요.";
  return "명부에서 빼지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}

function isStale(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    (err.code === "INSIGHT_ALREADY_REVIEWED" ||
      err.code === "INSIGHT_SUPERSEDED" ||
      err.code === "INSIGHT_NOT_PRIMARY_SOURCE" ||
      err.status === 404)
  );
}

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  switch (err.code) {
    case "INSIGHT_ALREADY_REVIEWED":
      return "이미 승인 또는 보류된 리포트예요. 목록을 새로 불러왔어요.";
    case "INSIGHT_SUPERSEDED":
      return "같은 기간의 더 새 리포트가 만들어졌어요. 목록에서 새 리포트를 읽어 주세요.";
    case "INSIGHT_NOT_PRIMARY_SOURCE":
      return "우리 기관이 승인하는 리포트가 아니에요. 학교 > 센터 > 활동지원사 순으로 한 곳이 승인해요.";
    case "INSIGHT_MATERIAL_CONSENT_REVOKED":
      return "근거를 낸 기관 중 보호자가 공유를 끈 곳이 있어 승인할 수 없어요. 보류는 할 수 있어요.";
  }
  if (err.status === 404) return "더 이상 볼 수 없는 리포트예요. 목록을 새로 불러왔어요.";
  if (err.status === 403) return "보안 확인에 실패했어요. 새로고침한 뒤 다시 시도해 주세요.";
  return "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}
