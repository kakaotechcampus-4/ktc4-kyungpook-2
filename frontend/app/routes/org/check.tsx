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
  ApiError,
  getBlockedQueue,
  getChildren,
  getMatchingQueue,
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

/**
 * 기록 확인 — 옛 매칭 확인(O-22·O-23)과 검증 수정 요청(O-24·O-25)을 한 화면에.
 * 두 칸 모두 한 줄 카드 목록이고, 누르면 처리 팝업이 뜬다. 처리하면 같은 칸 다음 건이 이어서 열린다.
 * 대시보드 바로가기는 `?open=confirm|blocked` (첫 건), `&id=` 를 붙이면 그 건을 연다.
 */
/** 칸 바꾸기 · `?open=` 지우기로는 다시 불러오지 않는다 (lib/revalidate) */
export const shouldRevalidate = skipSearchOnlyChange;

export async function clientLoader() {
  // 명부를 못 받아도 큐는 띄운다. "우리 기관 아이가 아니에요" 는 명부 없이도 처리할 수 있다
  const [confirms, blocked, rosterResult] = await Promise.all([
    getMatchingQueue(),
    getBlockedQueue(),
    getChildren().then(
      (roster) => ({ roster, rosterFailed: false }),
      () => ({ roster: [] as ChildListItem[], rosterFailed: true }),
    ),
  ]);
  // 로그인이 풀려 맡겨 둔 고치던 본문(lib/reinputDraft). 목록을 받은 뒤에 읽고, 지우지는 않는다(팝업이 그린 뒤 지움)
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

  // 대시보드 바로가기: ?open=confirm|blocked[&id=] → 그 건(없으면 첫 건) 팝업을 연 채로
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
    // 주소의 open 이 바뀔 때만 본다
  }, [params]);

  // 다시 로그인하고 돌아왔으면 맡겨 둔 기록 하나를 바로 연다(바로가기로 온 게 아니면)
  const [autoOpened, setAutoOpened] = useState(false);
  useEffect(() => {
    if (autoOpened || params.get("open")) return;
    setAutoOpened(true);
    const first = blocked.find((b) => b.id in draftsLogin.carried.drafts);
    if (first) bw.open(first.id);
  }, [autoOpened]);

  /** 처리한 건은 ✓ 2초 뒤 빠진다 — 그 뒤에 목록·메뉴 배지를 서버와 맞춘다 */
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

/* ── 아이 확인 ─────────────────────────────────── */

/**
 * 어느 파일의 일지인지. 파일 이름에 명부 아이 이름이 있었으면 그 표지 이름, 없으면 파일 이름.
 * 표지 이름이 없다고 "이름 없음" 이라 하지 않는다 — 일지 본문에는 이름이 있을 수 있다
 */
function sourceOf(item: MatchingItem): string {
  return item.hintName ? `표지 이름 '${item.hintName}'` : item.record.fileName;
}

/** AI 가 지목한 아이. review 는 matchedChildId 로만, 후보가 하나면 그 아이 */
function recommendedId(item: MatchingItem): string | null {
  if (item.matchedChildId) return item.matchedChildId;
  return item.candidates.length === 1 ? item.candidates[0].childId : null;
}

function nameOf(childId: string, item: MatchingItem, roster: ChildListItem[]): string | null {
  return (
    item.candidates.find((c) => c.childId === childId)?.name ?? roster.find((r) => r.id === childId)?.name ?? null
  );
}

/** AI 가 아이를 정하지 못한 이유 (AI/matching/nodes.py 의 decide) */
function whyUnsure(item: MatchingItem): string {
  if (item.status === "multi") {
    return item.multiReason === "co_mention"
      ? "한 기록에 여러 아이가 함께 나와요. 대표 아이를 골라 주세요."
      : "이름만으로는 구별되지 않아요. 생년월일을 보고 골라 주세요.";
  }
  // 못 찾은 이유(이름이 없음 · 명부에 없는 이름)는 서버가 주지 않는다 — 둘 다일 수 있게 말한다
  if (item.status === "unmatched") {
    return "AI가 누구의 기록인지 찾지 못했어요. 일지에 이름이 없거나, 아직 명부에 없는 아이일 수 있어요.";
  }
  if (item.status === "failed") return "AI 판정을 받지 못했어요.";
  return "AI가 한 명을 골랐지만 확정하지 않았어요.";
}

/** 서버(O-23)는 명부에 없거나 동의가 켜지지 않은 아이로는 확정을 거부한다 — 미리 막고 이유를 보인다 */
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
  // 추천 아이가 고를 수 있는 목록에 있을 때만 미리 고른다(명부에서 빠졌거나 동의 전이면 비워 둔다)
  const recPickable = rec != null && (recChild ? !pickBlocked(recChild) : rosterFailed);
  const [sel, setSel] = useState<string | null>(recPickable ? rec : null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 후보가 있으면 앞에 둔다. 명부를 못 받았으면 후보만으로 고른다
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

/* ── 고쳐야 할 기록 ─────────────────────────────── */

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
  /** 로그인이 풀려 맡겨 둔 이 기록의 고치던 본문 */
  carriedDraft?: ReinputDraft;
  /** 이 화면에서 401 을 받았다 — 고칠 때마다 맡겨 둔다 */
  loggedOut: boolean;
  onCarriedUsed: (id: string) => void;
  onLoggedOut: () => void;
  /** 요청이 서버에 닿았다(성공이거나 401 이 아닌 서버 응답) — 로그인이 살아 있다 */
  onReached: () => void;
}) {
  // 전체 본문이 없으면(미리보기만 옴) 고치게 두지 않는다 — 잘린 미리보기를 보내면 뒤쪽 본문이 사라진다
  const full = item.record.fullContent;
  const original = full ?? item.record.preview ?? "";
  // 맡겨 둔 본문은 원래 본문이 그대로일 때만 되살린다 — 그사이 바뀌었으면 옛 본문 위에 덮어쓰지 않는다
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

  useEffect(() => {
    if (carriedDraft) onCarriedUsed(item.id);
  }, [carriedDraft, item.id, onCarriedUsed]);

  // 로그인이 풀린 뒤로는 고칠 때마다 맡긴 본문을 갱신한다. 원래대로면 칸을 지운다
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
      // 통신 오류에서는 로그인 여부를 알 수 없다 — 맡긴 본문 보관을 유지한다
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
            <PopButton tone="dark" disabled={!changed || busy} onClick={() => run("revise")}>
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
            maxLength={MAX_REVISED_CONTENT}
            onChange={(e) => setDraft(e.target.value)}
            className="min-h-[130px] w-full resize-y rounded-[14px] border border-line2 bg-surface px-3.5 py-3 text-[15px] leading-[1.6] text-ink focus:border-accent focus:outline-2 focus:outline-accent"
          />
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
  // 이미 처리됐거나(400 OUTDATED·INVALID_REQUEST 외) 없어진 건 — 목록을 새로 받는다
  if (err.status === 400 || err.status === 404) return { text: "", stale: true };
  return { text: "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.", stale: false };
}
