import { useEffect, useMemo, useState } from "react";
import { skipSearchOnlyChange } from "@/lib/revalidate";
import { useLoaderData, useRevalidator, useSearchParams } from "react-router";
import { Busy, ConfirmDialog, ReloginButton } from "@/components/ui";
import { ListPopup, useWalk } from "@/components/ui/ListPopup";
import { Pager, usePage } from "@/components/ui/Pager";
import {
  Chip,
  FixedRows,
  PopButton,
  PopSection,
  SectionHead,
  WorkHead,
  WorkRow,
} from "@/components/org/WorkList";
import {
  AGENT_QUEUE_FULL_TEXT,
  ApiError,
  getBlockedQueue,
  getChildren,
  getMatchingQueue,
  isAgentQueueFull,
  MAX_REVISED_CONTENT,
  resolveBlockedItem,
  resolveMatchingItem,
  reviseBlockedItem,
} from "@/lib/api";
import { isLoginLost } from "@/lib/apiError";
import { RESTORED, splitCarried, useCarried } from "@/lib/carriedDraft";
import { formatMonthDay } from "@/lib/datetime";
import { forgetReinputDrafts, readReinputDrafts, saveReinputDraft, type ReinputDraft } from "@/lib/reinputDraft";
import type { BlockedItem, ChildListItem, MatchingItem, MatchResolution } from "@/lib/types";

export const shouldRevalidate = skipSearchOnlyChange;

export async function clientLoader() {
  const [confirms, blocked, rosterResult] = await Promise.all([
    getMatchingQueue(),
    getBlockedQueue(),
    getChildren().then(
      (roster) => ({ roster, rosterFailed: false }),
      () => ({ roster: [] as ChildListItem[], rosterFailed: true }),
    ),
  ]);
  const carried = splitCarried(readReinputDrafts(), blocked.map((b) => b.id));
  return { confirms, blocked, ...rosterResult, carried };
}

const DROPPED = "로그인하는 사이 기록이 바뀌어 고치던 내용은 지웠어요.";

const PAGE = 5;
const idOf = (x: { id: string }) => x.id;

function dayOf(value: string | null): string {
  return value ? formatMonthDay(value) : "날짜 모름";
}

export default function CheckPage() {
  const { confirms, blocked, roster, rosterFailed, carried: loadedCarried } = useLoaderData<typeof clientLoader>();
  const draftsLogin = useCarried(loadedCarried, forgetReinputDrafts, readReinputDrafts);
  const revalidator = useRevalidator();
  const [params, setParams] = useSearchParams();

  const cw = useWalk(confirms, idOf);
  const bw = useWalk(blocked, idOf);
  const cPage = usePage(cw.visible, PAGE);
  const bPage = usePage(bw.visible, PAGE);

  useEffect(() => {
    const open = params.get("open");
    if (!open) return;
    const id = params.get("id");
    const walk = open === "blocked" ? bw : open === "confirm" ? cw : null;
    const list = open === "blocked" ? blocked : confirms;
    if (walk) {
      const target = id ? list.find((x) => x.id === id) : list[0];
      if (target) walk.open(target.id);
    }
    setParams({}, { replace: true });
  }, [params]);

  const [autoOpened, setAutoOpened] = useState(false);
  useEffect(() => {
    if (autoOpened || params.get("open")) return;
    setAutoOpened(true);
    const first = blocked.find((b) => b.id in draftsLogin.carried.drafts);
    if (first) bw.open(first.id);
  }, [autoOpened]);

  function settle(done: (id: string) => void, id: string) {
    done(id);
    setTimeout(() => revalidator.revalidate(), 2100);
  }

  const cLeft = cw.visible.filter((c) => !cw.isDone(c.id)).length;
  const bLeft = bw.visible.filter((b) => !bw.isDone(b.id)).length;
  const jobs = [cLeft ? `아이 확인 ${cLeft}건` : "", bLeft ? `고쳐야 할 기록 ${bLeft}건` : ""].filter(Boolean);

  return (
    <div>
      <WorkHead title="기록 확인" jobs={jobs} tour="check" />

      {draftsLogin.carried.gone.length > 0 ? (
        <p role="status" className="mb-4 rounded-[14px] bg-humansoft px-4 py-3 text-[14px] text-human">
          로그인하는 사이 {draftsLogin.carried.gone.length}건은 이미 처리돼 고치던 내용을 지웠어요.
        </p>
      ) : null}

      <section data-tour="confirm" className="mb-8 grid gap-2.5">
        <SectionHead title="아이 확인" count={cLeft} />
        <FixedRows
          size={PAGE}
          count={cPage.items.length}
          empty="아이를 확인할 기록이 없어요"
          pager={<Pager {...cPage} onChange={cPage.setPage} />}
        >
          {cPage.items.map((c) => (
            <WorkRow
              key={c.id}
              who={dayOf(c.record.capturedAt)}
              sub={sourceOf(c)}
              text={c.record.preview ?? c.record.fullContent ?? ""}
              tag={
                recommendedId(c) ? (
                  <Chip tone="ai">AI 추천 {nameOf(recommendedId(c)!, c, roster) ?? ""}</Chip>
                ) : (
                  <Chip tone="human">직접 골라야 해요</Chip>
                )
              }
              action="확인하기"
              onOpen={() => cw.open(c.id)}
              done={cw.isDone(c.id) ? `${dayOf(c.record.capturedAt)} · 처리했어요` : undefined}
            />
          ))}
        </FixedRows>
      </section>

      <section data-tour="blocked" className="grid gap-2.5">
        <SectionHead title="고쳐야 할 기록" count={bLeft} />
        <FixedRows
          size={PAGE}
          count={bPage.items.length}
          empty="막힌 기록이 없어요"
          pager={<Pager {...bPage} onChange={bPage.setPage} />}
        >
          {bPage.items.map((b) => (
            <WorkRow
              key={b.id}
              who={b.childName ?? "아이 미정"}
              sub={dayOf(b.record.capturedAt)}
              text={b.record.preview ?? b.record.fullContent ?? ""}
              tag={<Chip tone="bad">{b.violationCode?.[0] ?? "확인 필요"}</Chip>}
              action="고치기"
              onOpen={() => bw.open(b.id)}
              done={bw.isDone(b.id) ? `${b.childName ?? "아이 미정"} ${dayOf(b.record.capturedAt)} · 처리했어요` : undefined}
            />
          ))}
        </FixedRows>
      </section>

      {cw.current ? (
        <ConfirmPopup
          key={cw.current.id}
          item={cw.current}
          position={cw.position}
          roster={roster}
          rosterFailed={rosterFailed}
          onClose={cw.close}
          onDone={(id) => settle(cw.done, id)}
          onStale={() => {
            cw.close();
            revalidator.revalidate();
          }}
        />
      ) : null}
      {bw.current ? (
        <BlockedPopup
          key={bw.current.id}
          item={bw.current}
          position={bw.position}
          onClose={bw.close}
          onDone={(id) => settle(bw.done, id)}
          onStale={() => {
            bw.close();
            revalidator.revalidate();
          }}
          carriedDraft={draftsLogin.carried.drafts[bw.current.id]}
          loggedOut={draftsLogin.loggedOut}
          onCarriedUsed={draftsLogin.used}
          onLoggedOut={draftsLogin.lost}
          onReached={draftsLogin.reached}
        />
      ) : null}
    </div>
  );
}

function sourceOf(item: MatchingItem): string {
  return item.hintName ? `표지 이름 '${item.hintName}'` : item.record.fileName;
}

function recommendedId(item: MatchingItem): string | null {
  if (item.matchedChildId) return item.matchedChildId;
  return item.candidates.length === 1 ? item.candidates[0].childId : null;
}

function nameOf(childId: string, item: MatchingItem, roster: ChildListItem[]): string | null {
  return (
    item.candidates.find((c) => c.childId === childId)?.name ?? roster.find((r) => r.id === childId)?.name ?? null
  );
}

function whyUnsure(item: MatchingItem): string {
  if (item.status === "multi") {
    return item.multiReason === "co_mention"
      ? "한 기록에 여러 아이가 함께 나와요. 대표 아이를 골라 주세요."
      : "이름만으로는 구별되지 않아요. 생년월일을 보고 골라 주세요.";
  }
  if (item.status === "unmatched") {
    return "AI가 누구의 기록인지 찾지 못했어요. 일지에 이름이 없거나, 아직 명부에 없는 아이일 수 있어요.";
  }
  if (item.status === "failed") return "AI 판정을 받지 못했어요.";
  return "AI가 한 명을 골랐지만 확정하지 않았어요.";
}

function pickBlocked(c: ChildListItem): string | null {
  if (c.consent === "not_granted") return "보호자 동의 전";
  if (c.consent !== "granted") return "기록 중단";
  return null;
}

function ConfirmPopup({
  item,
  position,
  roster,
  rosterFailed,
  onClose,
  onDone,
  onStale,
}: {
  item: MatchingItem;
  position?: { index: number; total: number };
  roster: ChildListItem[];
  rosterFailed: boolean;
  onClose: () => void;
  onDone: (id: string) => void;
  onStale: () => void;
}) {
  const rec = recommendedId(item);
  const recChild = rec ? roster.find((r) => r.id === rec) : undefined;
  const recPickable = rec != null && (recChild ? !pickBlocked(recChild) : rosterFailed);
  const [sel, setSel] = useState<string | null>(recPickable ? rec : null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const kids = useMemo(() => {
    const first = new Set(item.candidates.map((c) => c.childId).concat(rec ? [rec] : []));
    const fromRoster = roster.map((r) => ({ id: r.id, name: r.name, birth: r.birthDate, blocked: pickBlocked(r) }));
    const known = new Set(fromRoster.map((k) => k.id));
    const extra = rosterFailed
      ? item.candidates
          .filter((c) => !known.has(c.childId))
          .map((c) => ({ id: c.childId, name: c.name ?? "이름 없음", birth: c.birthDate ?? "", blocked: null }))
      : [];
    return [...extra, ...fromRoster].sort((a, b) => Number(first.has(b.id)) - Number(first.has(a.id)));
  }, [item, rec, roster, rosterFailed]);
  const selName = kids.find((k) => k.id === sel)?.name ?? null;

  async function submit(resolution: MatchResolution) {
    setBusy(true);
    setError(null);
    try {
      await resolveMatchingItem(item.id, resolution);
      onDone(item.id);
    } catch (err) {
      if (err instanceof ApiError && (err.status === 404 || err.status === 409)) {
        onStale();
        return;
      }
      if (isAgentQueueFull(err)) {
        setError(AGENT_QUEUE_FULL_TEXT);
        return;
      }
      setError(
        err instanceof ApiError && err.status === 400
          ? "이 아이로는 연결할 수 없어요. 명부와 보호자 동의를 확인해 주세요."
          : "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
      );
    } finally {
      setBusy(false);
    }
  }

  const recName = rec ? nameOf(rec, item, roster) : null;
  const recBirth = rec ? (item.candidates.find((c) => c.childId === rec)?.birthDate ?? recChild?.birthDate) : null;

  return (
    <ListPopup
      title="아이 확인"
      subtitle={`${dayOf(item.record.capturedAt)} 기록 · ${sourceOf(item)}`}
      position={position}
      busy={busy}
      onClose={onClose}
      footer={
        <>
          <PopButton onClick={() => submit({ action: "not_ours" })} disabled={busy}>
            우리 기관 아이가 아니에요
          </PopButton>
          <span className="flex-1" />
          {error ? <span className="basis-full text-[14px] font-bold text-block sm:basis-auto">{error}</span> : null}
          <PopButton tone="dark" disabled={!sel || busy} onClick={() => sel && submit({ action: "assign", childId: sel })}>
            {busy ? <Busy>처리 중…</Busy> : selName ? `${selName}에게 연결` : "아이를 골라 주세요"}
          </PopButton>
        </>
      }
    >
      <PopSection title="일지 내용">
        <div className="rounded-[14px] border border-line bg-paper px-4 py-3.5">
          <p className="max-w-[62ch] text-[16px] leading-[1.75] whitespace-pre-wrap">
            {item.record.fullContent ?? item.record.preview ?? ""}
          </p>
        </div>
      </PopSection>
      {rec && item.status === "review" ? (
        <div className="rounded-[14px] bg-accentsoft px-4 py-3 text-[15px]">
          <span className="text-[13px] text-muted">AI 추천</span> <b>{recName ?? "명부에 없는 아이"}</b>{" "}
          <span className="text-ink2">{recBirth ?? ""}</span>
        </div>
      ) : (
        <div className="rounded-[14px] bg-humansoft px-3.5 py-3 text-[14px] leading-normal text-human">
          {whyUnsure(item)} 명부에서 직접 골라 주세요.
        </div>
      )}
      {rosterFailed ? (
        <p className="text-[14px] font-bold text-block">명부를 불러오지 못했어요. 새로고침하면 전체 명부에서 고를 수 있어요.</p>
      ) : null}
      <PopSection title="어느 아이의 기록인가요?">
        <div role="radiogroup" aria-label="아이 고르기" className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-2">
          {kids.map((k) => {
            const on = sel === k.id;
            return (
              <button
                key={k.id}
                type="button"
                role="radio"
                aria-checked={on}
                disabled={!!k.blocked}
                title={k.blocked ?? undefined}
                onClick={() => setSel(k.id)}
                className={`grid rounded-[14px] border px-3 py-2.5 text-left disabled:opacity-45 ${
                  on ? "border-accent bg-accentsoft shadow-[inset_0_0_0_1px_var(--color-accent)]" : "border-line2 bg-surface hover:bg-surface2"
                }`}
              >
                <b>{k.name}</b>
                <span className="text-[12px] text-muted">{k.blocked ?? k.birth}</span>
              </button>
            );
          })}
        </div>
      </PopSection>
    </ListPopup>
  );
}

function BlockedPopup({
  item,
  position,
  onClose,
  onDone,
  onStale,
  carriedDraft,
  loggedOut,
  onCarriedUsed,
  onLoggedOut,
  onReached,
}: {
  item: BlockedItem;
  position?: { index: number; total: number };
  onClose: () => void;
  onDone: (id: string) => void;
  onStale: () => void;
  carriedDraft?: ReinputDraft;
  loggedOut: boolean;
  onCarriedUsed: (id: string) => void;
  onLoggedOut: () => void;
  onReached: () => void;
}) {
  const full = item.record.fullContent;
  const original = full ?? item.record.preview ?? "";
  const [restored] = useState(() =>
    carriedDraft && full != null && carriedDraft.original === full ? carriedDraft.draft : null,
  );
  const [carriedNote] = useState(() => (carriedDraft ? (restored != null ? RESTORED : DROPPED) : null));
  const [draft, setDraft] = useState(restored ?? original);
  const [busy, setBusy] = useState(false);
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loginLost, setLoginLost] = useState(false);
  const changed = full != null && draft.trim() !== "" && draft.trim() !== original.trim();
  const tooLong = draft.length > MAX_REVISED_CONTENT;

  useEffect(() => {
    if (carriedDraft) onCarriedUsed(item.id);
  }, [carriedDraft, item.id, onCarriedUsed]);

  useEffect(() => {
    if (!loggedOut) return;
    saveReinputDraft(item.id, changed && full != null ? { original: full, draft } : null);
  }, [loggedOut, changed, full, draft, item.id]);

  async function run(action: "revise" | "drop") {
    setBusy(true);
    setError(null);
    setLoginLost(false);
    try {
      if (action === "revise") await reviseBlockedItem(item.id, draft);
      else await resolveBlockedItem(item.id, "hold");
      setAsking(false);
      onReached();
      onDone(item.id);
    } catch (err) {
      setAsking(false);
      if (isLoginLost(err)) {
        setLoginLost(true);
        setError("로그인이 풀렸어요. 다시 로그인하면 고치던 내용을 되살려요.");
        onLoggedOut();
        return;
      }
      if (err instanceof ApiError) onReached();
      const f = failureOf(err);
      if (f.stale) onStale();
      else setError(f.text);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <ListPopup
        title="고쳐야 할 기록"
        subtitle={`${item.childName ?? "아이 미정"} · ${dayOf(item.record.capturedAt)}`}
        position={position}
        busy={busy}
        onClose={onClose}
        footer={
          <>
            <PopButton onClick={() => setAsking(true)} disabled={busy}>
              이 기록 빼기
            </PopButton>
            <span className="flex-1" />
            {error ? (
              <span className="flex basis-full flex-wrap items-center gap-x-3 gap-y-1 sm:basis-auto">
                <span role="alert" className="text-[14px] font-bold text-block">
                  {error}
                </span>
                {loginLost ? <ReloginButton role="org" returnTo="/check" /> : null}
              </span>
            ) : null}
            <PopButton tone="dark" disabled={!changed || tooLong || busy} onClick={() => run("revise")}>
              {busy ? <Busy>보내는 중…</Busy> : "다시 보내기"}
            </PopButton>
          </>
        }
      >
        {carriedNote ? (
          <p role="status" className="rounded-[14px] bg-humansoft px-3.5 py-3 text-[14px] leading-6 text-human">
            {carriedNote}
          </p>
        ) : null}
        <div className="rounded-[14px] bg-blocksoft px-3.5 py-3 text-[14px] text-block">
          {item.violationCode?.length ? <b>{item.violationCode.join(" · ")} · </b> : null}
          {item.violationReason}
        </div>
        <PopSection title="문장 고치기">
          {full == null ? (
            <p className="mb-2 text-[14px] font-bold text-block">전체 본문을 불러오지 못해 앞부분만 보여요. 본문을 고칠 수 없어 이 기록은 빼 주세요.</p>
          ) : null}
          <textarea
            aria-label="고칠 문장"
            value={draft}
            readOnly={full == null}
            aria-invalid={tooLong}
            aria-describedby="revise-length"
            onChange={(e) => setDraft(e.target.value)}
            className="min-h-[130px] w-full resize-y rounded-[14px] border border-line2 bg-surface px-3.5 py-3 text-[15px] leading-[1.6] text-ink focus:border-accent focus:outline-2 focus:outline-accent"
          />
          <p
            id="revise-length"
            className={`mt-1 text-right text-[13px] ${tooLong ? "font-bold text-block" : "text-ink2"}`}
          >
            {tooLong ? "5,000자까지 보낼 수 있어요 · " : null}
            {draft.length.toLocaleString("ko-KR")} / {MAX_REVISED_CONTENT.toLocaleString("ko-KR")}자
          </p>
        </PopSection>
      </ListPopup>
      {asking ? (
        <ConfirmDialog
          title="이 기록을 뺄까요?"
          warning="되돌릴 수 없어요"
          confirmLabel="이 기록 빼기"
          tone="human"
          busy={busy}
          onCancel={() => setAsking(false)}
          onConfirm={() => run("drop")}
        >
          요약과 리포트에 쓰지 않아요.
        </ConfirmDialog>
      ) : null}
    </>
  );
}

function failureOf(err: unknown): { text: string; stale: boolean } {
  if (!(err instanceof ApiError)) return { text: "네트워크 연결을 확인하고 다시 시도해 주세요.", stale: false };
  if (isAgentQueueFull(err)) return { text: AGENT_QUEUE_FULL_TEXT, stale: false };
  if (err.status === 400) {
    switch (err.code) {
      case "INVALID_REQUEST":
        return { text: "본문이 비어 있거나 너무 길어요.", stale: false };
      case "VALIDATION_RESULT_CONTENT_UNCHANGED":
        return { text: "고친 내용이 없어요. 막힌 이유에 적힌 부분을 고쳐 주세요.", stale: false };
      case "VALIDATION_RESULT_CHILD_NOT_SHARED":
        return { text: "아이가 명부에서 빠졌거나 공유 동의가 꺼져 다시 확인할 수 없어요. 이 기록은 빼 주세요.", stale: false };
    }
  }
  if (err.status === 400 || err.status === 404) return { text: "", stale: true };
  return { text: "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.", stale: false };
}
