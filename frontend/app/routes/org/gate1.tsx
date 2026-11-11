import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { skipSearchOnlyChange } from "@/lib/revalidate";
import { Link, useLoaderData, useRevalidator, useRouteLoaderData, useSearchParams } from "react-router";
import { Check, ChevronRight, Pencil, Plus, Trash2, TriangleAlert } from "lucide-react";
import { NotReadyPage } from "@/components/org/NotReady";
import { Busy, ConfirmDialog, ReloginButton, Spinner } from "@/components/ui";
import { ListPopup, useWalk } from "@/components/ui/ListPopup";
import { Pager, usePage } from "@/components/ui/Pager";
import {
  Chip,
  EmptyLine,
  PopButton,
  PopSection,
  Toast,
  WorkHead,
  WorkRow,
} from "@/components/org/WorkList";
import {
  AGENT_QUEUE_FULL_TEXT,
  ApiError,
  closeFailedSummary,
  decideGate1,
  getFailedSummaries,
  getGate1Queue,
  isReady,
  loadIfReady,
  reopenGate1,
  retryFailedSummary,
} from "@/lib/api";
import { isLoginLost } from "@/lib/apiError";
import { RESTORED, splitCarried, useCarried } from "@/lib/carriedDraft";
import { locateEvidence, markSegments } from "@/lib/evidence";
import { forgetGate1Drafts, readGate1Drafts, saveGate1Draft, type Gate1Draft } from "@/lib/gate1Draft";
import { notify } from "@/lib/notice";
import type { FailedSummary, Gate1Card, SummaryClaim, SummaryItem } from "@/lib/types";
import type { clientLoader as orgLayoutLoader } from "./layout";

export const shouldRevalidate = skipSearchOnlyChange;

export async function clientLoader() {
  const [pending, held, failed] = await Promise.all([
    getGate1Queue("pending"),
    getGate1Queue("hold"),
    loadIfReady("summaryFailures", getFailedSummaries).catch(() => null),
  ]);
  const carried = splitCarried(readGate1Drafts(), pending.map((s) => s.id));
  return { pending, held, failed: failed ?? [], carried };
}

const DROPPED = "로그인하는 사이 요약이 바뀌어 고치던 내용은 지웠어요.";

function restoredOf(item: SummaryItem, draft: Gate1Draft | undefined): { lines: Line[]; added: Added[] } | null {
  if (!draft || item.noContent) return null;
  if (draft.version !== item.version || draft.lines.length !== item.claims.length) return null;
  return {
    lines: draft.lines.map((l) => ({ action: l.action, text: l.text })),
    added: draft.added.map((text, key) => ({ key, text })),
  };
}

const TITLE = "요약 검토";

type Tab = "pending" | "hold";
type Decision = "approve" | "hold" | "remove";
type Outcome =
  | "approved"
  | "corrected"
  | "held"
  | "removed"
  | "reopened"
  | "confirmed"
  | "reverted"
  | "retried"
  | "closed"
  | "stale";

interface Line {
  action: "KEEP" | "EDIT" | "DELETE";
  text: string;
}

interface Added {
  key: number;
  text: string;
}

const MAX_CARDS = 100;
const MAX_TEXT = 1000;

const WEEKDAY = "일월화수목금토";
function dateLabel(ymd: string): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return `${m}월 ${d}일 (${WEEKDAY[new Date(y, m - 1, d).getDay()]})`;
}

const REVIEW_REASON: Record<string, string> = {
  다른아동이름: "다른 아이 이름이 들어 있을 수 있어요",
};

function freshLines(claims: SummaryClaim[]): Line[] {
  return claims.map(() => ({ action: "KEEP", text: "" }));
}

function isEdited(line: Line, claim: SummaryClaim): boolean {
  const text = line.text.trim();
  return line.action === "EDIT" && text !== "" && text !== claim.text.trim();
}

function buildCards(claims: SummaryClaim[], lines: Line[], added: Added[]): Gate1Card[] | undefined {
  const cards: Gate1Card[] = claims.map((claim, i) => {
    const line = lines[i];
    if (line.action === "DELETE") return { index: claim.index, action: "DELETE" };
    if (isEdited(line, claim)) return { index: claim.index, action: "EDIT", text: line.text.trim() };
    return { index: claim.index, action: "KEEP" };
  });
  for (const a of added) {
    if (a.text.trim()) cards.push({ action: "ADD", text: a.text.trim() });
  }
  return cards.some((c) => c.action !== "KEEP") ? cards : undefined;
}

function isStale(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    (err.code === "SUMMARY_ALREADY_REVIEWED" ||
      err.code === "SUMMARY_HELD" ||
      err.code === "SUMMARY_NOT_HELD" ||
      err.status === 404)
  );
}

function needsReload(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    (err.code === "SUMMARY_CHANGED" ||
      err.code === "SUMMARY_REGENERATING" ||
      err.code === "SUMMARY_INVALID_REQUEST")
  );
}

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  switch (err.code) {
    case "SUMMARY_ALREADY_REVIEWED":
      return "이미 처리된 요약이에요. 목록을 새로 불러왔어요.";
    case "SUMMARY_HELD":
      return "보류된 요약이에요. 보류함에서 보류를 해제한 뒤 승인해 주세요.";
    case "SUMMARY_NOT_HELD":
      return "이미 보류가 풀린 요약이에요. 검토 대기에서 확인해 주세요.";
    case "SUMMARY_CHANGED":
      return "요약이 새 기록으로 바뀌었어요. 바뀐 문장을 다시 읽고 결정해 주세요.";
    case "SUMMARY_REGENERATING":
      return "새 기록을 반영해 요약을 다시 만드는 중이에요. 잠시 뒤 다시 시도해 주세요.";
    case "SUMMARY_INVALID_REQUEST":
      return "고친 문장이 지금 요약과 맞지 않아요. 목록을 새로 불러왔으니 다시 확인해 주세요.";
    case "INVALID_REQUEST":
      return "고친 문장을 보내지 못했어요. 빈 문장이나 1000자가 넘는 문장이 없는지 확인해 주세요.";
    case "AGENT_QUEUE_FULL":
      return AGENT_QUEUE_FULL_TEXT;
  }
  if (err.status === 404) return "더 이상 볼 수 없는 요약이에요. 목록을 새로 불러왔어요.";
  if (err.status === 401) return "로그인이 풀렸어요. 다시 로그인한 뒤 시도해 주세요.";
  if (err.status === 403) return "보안 확인에 실패했어요. 새로고침한 뒤 다시 시도해 주세요.";
  return "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}


function NumberDot({ n, className }: { n: number; className: string }) {
  return (
    <span
      aria-hidden
      className={`flex size-[26px] shrink-0 items-center justify-center rounded-full text-[13px] font-bold ${className}`}
    >
      {n}
    </span>
  );
}


const SEGMENT = [
  { action: "KEEP", label: "유지", Icon: Check, on: "bg-ink2 text-white" },
  { action: "EDIT", label: "수정", Icon: Pencil, on: "bg-ink text-paper" },
  { action: "DELETE", label: "삭제", Icon: Trash2, on: "bg-block text-white" },
] as const;

function flagsByClaim(item: SummaryItem): Map<number, string[]> {
  const byClaim = new Map<number, string[]>();
  for (const flag of item.reviewFlags) {
    for (const index of flag.claimIndexes) {
      byClaim.set(index, [...new Set([...(byClaim.get(index) ?? []), ...flag.reasons])]);
    }
  }
  return byClaim;
}

function flagPlace(item: SummaryItem, claimIndexes: number[]): string {
  const nos = claimIndexes
    .map((index) => item.claims.findIndex((c) => c.index === index))
    .filter((at) => at >= 0)
    .map((at) => at + 1);
  return nos.length > 0 ? `${nos.join(" · ")}번 문장` : "원본 일지";
}

function flagChipText(reasons: string[]): string {
  if (reasons.length === 0) return "검증이 확인을 요청했어요";
  if (reasons.length === 1) return reasons[0];
  return `검증 사유 ${reasons.length}가지 · 위 안내를 봐 주세요`;
}

function SentenceRow({
  no,
  claim,
  line,
  onChange,
  evOn = false,
  onEv,
  flag,
}: {
  no: number;
  claim: SummaryClaim;
  line: Line;
  onChange: (patch: Partial<Line>) => void;
  evOn?: boolean;
  onEv?: () => void;
  flag?: string[];
}) {
  const isEdit = line.action === "EDIT";
  const isDelete = line.action === "DELETE";
  const changed = isEdited(line, claim);

  const row = isDelete
    ? "border-line bg-paper"
    : isEdit
      ? "border-accent/35 bg-accentsoft/35"
      : flag
        ? "border-human/60 bg-humansoft/25"
        : "border-line bg-surface";
  const dot = isEdit
    ? "bg-ink text-paper"
    : isDelete
      ? "bg-blocksoft text-block"
      : "bg-surface2 text-ink2";

  return (
    <li className={`flex flex-wrap items-start gap-3 rounded border py-3 pr-3 pl-3.5 ${row} ${evOn ? "shadow-[inset_0_0_0_2px_var(--color-mark)]" : ""}`}>
      <span className="mt-2">
        <NumberDot n={no} className={dot} />
      </span>
      <div className={`flex min-w-0 flex-[1_1_320px] flex-col gap-1.5 ${isEdit ? "" : "pt-2"}`}>
        {isEdit ? (
          <textarea
            aria-label={`${no}번 문장 고치기`}
            autoFocus
            rows={2}
            maxLength={MAX_TEXT}
            value={line.text}
            onChange={(e) => onChange({ text: e.target.value })}
            className="w-full resize-y rounded border border-accent bg-surface px-3 py-2.5 text-[16px] leading-6 outline-none focus:ring-2 focus:ring-accent/20"
          />
        ) : (
          <p
            className={`text-[16px] leading-relaxed ${isDelete ? "text-muted line-through" : "text-ink"}`}
          >
            {claim.text}
          </p>
        )}
        {flag ? (
          <span className="flex items-start gap-1 self-start rounded bg-humansoft px-2 py-0.5 text-[12px] font-bold text-human">
            <TriangleAlert aria-hidden size={13} strokeWidth={2} className="mt-[3px] shrink-0" />
            {flagChipText(flag)}
          </span>
        ) : null}
        {changed ? (
          <p className="text-[13px] text-muted">
            원문 · <span className="line-through">{claim.text}</span>
          </p>
        ) : null}
        {changed || isDelete ? (
          <span
            className={`self-start rounded px-2 py-0.5 text-[12px] font-bold ${
              isDelete ? "bg-blocksoft text-block" : "bg-accentsoft text-accentink"
            }`}
          >
            {isDelete ? "삭제함" : "수정함"}
          </span>
        ) : null}
      </div>
      {onEv ? (
        <button
          type="button"
          aria-pressed={evOn}
          onClick={onEv}
          className={`min-h-10 shrink-0 rounded-full border px-3 text-[13px] font-bold ${
            evOn ? "border-mark bg-mark text-human" : "border-line2 bg-surface text-ink2 hover:bg-surface2"
          }`}
        >
          근거
        </button>
      ) : null}
      <div
        role="group"
        aria-label={`${no}번 문장 처리`}
        className="flex shrink-0 overflow-hidden rounded border border-line2 bg-surface"
      >
        {SEGMENT.map(({ action, label, Icon, on }, k) => {
          const pressed = line.action === action;
          return (
            <button
              key={action}
              type="button"
              aria-pressed={pressed}
              onClick={() =>
                onChange(
                  action === "EDIT" ? { action, text: line.text || claim.text } : { action },
                )
              }
              className={`flex min-h-10 items-center gap-1.5 px-3 text-[14px] font-semibold ${
                k > 0 ? "border-l border-line2" : ""
              } ${pressed ? on : "bg-surface text-ink2 hover:bg-surface2"}`}
            >
              <Icon aria-hidden size={14} strokeWidth={1.9} />
              {label}
            </button>
          );
        })}
      </div>
    </li>
  );
}

interface Row {
  id: string;
  childName: string;
  date: string;
  s?: SummaryItem;
  f?: FailedSummary;
}
const failedId = (childId: string, date: string) => `failed:${childId}:${date}`;
const rowOf = (s: SummaryItem): Row => ({ id: s.id, childName: s.childName, date: s.entryDate, s });
const idOf = (r: Row) => r.id;
const isProcessing = (r: Row) => !!r.s?.processing;

const OUTCOME: Record<Exclude<Outcome, "stale">, string> = {
  approved: "AI 요약 그대로 승인했어요",
  corrected: "고친 내용으로 승인했어요",
  held: "보류로 옮겼어요",
  removed: "요약을 제거했어요",
  reopened: "보류를 풀었어요 · 전체 칸에서 다시 볼 수 있어요",
  confirmed: "요약 없이 마무리했어요",
  reverted: "다시 요약하도록 돌렸어요",
  retried: "다시 요약하도록 돌렸어요",
  closed: "요약 없이 마무리했어요",
};

export default function Gate1Route() {
  if (!isReady("gate1")) return <NotReadyPage title={TITLE} />;
  return <Gate1Page />;
}

function Gate1Page() {
  const { pending, held, failed, carried: loadedCarried } = useLoaderData<typeof clientLoader>();
  const draftsLogin = useCarried(loadedCarried, forgetGate1Drafts, readGate1Drafts);
  const login = useMemo(() => ({ lost: draftsLogin.lost, reached: draftsLogin.reached }), [draftsLogin.lost, draftsLogin.reached]);
  const revalidator = useRevalidator();
  const [params, setParams] = useSearchParams();
  const tab: Tab = params.get("tab") === "hold" ? "hold" : "pending";
  const [toast, setToast] = useState<string | null>(null);
  const clearToast = useCallback(() => setToast(null), []);
  const [outcomes, setOutcomes] = useState<Record<string, string>>({});
  const sends = useRef(new Map<string, { timer: ReturnType<typeof setTimeout>; send: (leaving: boolean) => void; message: string }>());
  const [, setSendsTick] = useState(0);
  const bump = () => setSendsTick((n) => n + 1);
  const drafts = useRef(new Map<string, { version: string; draft: Draft }>());
  const keepaliveUsed = useRef(0);
  const lastApprove = useRef(0);

  const pendingRows = useMemo(
    () =>
      [
        ...failed.map((f): Row => ({ id: failedId(f.childId, f.entryDate), childName: f.childName, date: f.entryDate, f })),
        ...pending.map(rowOf),
      ].sort((a, b) => a.date.localeCompare(b.date)),
    [failed, pending],
  );
  const heldRows = useMemo(() => held.map(rowOf), [held]);

  const pw = useWalk(pendingRows, idOf, isProcessing);
  const hw = useWalk(heldRows, idOf);
  const walk = tab === "hold" ? hw : pw;
  const page = usePage(walk.visible);

  function setTab(next: Tab) {
    setParams(next === "hold" ? { tab: "hold" } : {}, { replace: true, preventScrollReset: true });
  }

  useEffect(() => {
    const open = params.get("open");
    if (!open) return;
    const list = (tab === "hold" ? heldRows : pendingRows).filter((r) => !isProcessing(r));
    const id = params.get("id");
    const want =
      open === "summary" && id
        ? id
        : open === "failed"
          ? failedId(params.get("child") ?? "", params.get("date") ?? "")
          : null;
    const target = want ? list.find((r) => r.id === want) : open === "first" ? list[0] : undefined;
    if (target) walk.open(target.id);
    setParams(tab === "hold" ? { tab: "hold" } : {}, { replace: true });
  }, [params]);

  const [autoOpened, setAutoOpened] = useState(false);
  useEffect(() => {
    if (autoOpened || params.get("open")) return;
    setAutoOpened(true);
    const first = pending.find((s) => s.id in draftsLogin.carried.drafts && !s.processing);
    if (first && tab === "pending") pw.open(first.id);
  }, [autoOpened]);

  function finish(id: string, outcome: Exclude<Outcome, "stale">) {
    setOutcomes((o) => ({ ...o, [id]: OUTCOME[outcome] }));
    walk.done(id);
    setTimeout(() => revalidator.revalidate(), 2100);
  }

  function approveLater(item: SummaryItem, cards: Gate1Card[] | undefined, draft: Draft) {
    const now = Date.now();
    if (now - lastApprove.current < 700) return;
    lastApprove.current = now;
    const outcome = cards ? "corrected" : "approved";
    setOutcomes((o) => ({ ...o, [item.id]: OUTCOME[outcome] }));
    walk.done(item.id);
    drafts.current.set(item.id, { version: item.version, draft });
    const body = { decision: "approve" as const, version: item.version, ...(cards ? { cards } : {}) };
    const send = (leaving: boolean) => {
      const entry = sends.current.get(item.id);
      if (!entry) return;
      clearTimeout(entry.timer);
      sends.current.delete(item.id);
      bump();
      const bytes = new TextEncoder().encode(JSON.stringify(body)).length;
      const keepalive = leaving && keepaliveUsed.current + bytes < 60_000;
      if (keepalive) keepaliveUsed.current += bytes;
      decideGate1(item.id, body, { keepalive })
        .then(() => {
          drafts.current.delete(item.id);
          login.reached();
        })
        .catch((err) => {
          pw.undo(item.id);
          if (isLoginLost(err)) {
            saveGate1Draft(item.id, { version: item.version, lines: draft.lines, added: draft.added.map((a) => a.text) });
            login.lost();
            notify(`로그인이 풀려 ${item.childName} 승인을 보내지 못했어요. 다시 로그인하면 고치던 내용을 되살려요`);
            return;
          }
          if (err instanceof ApiError) login.reached();
          notify(
            err instanceof ApiError && (err.code === "SUMMARY_REGENERATING" || err.code === "SUMMARY_CHANGED")
              ? `${item.childName} 요약에 새 기록이 들어와 승인하지 못했어요. 검토에서 다시 읽고 승인해 주세요`
              : `${item.childName} 승인을 보내지 못했어요. ${messageOf(err)}`,
          );
        })
        .finally(() => revalidator.revalidate());
    };
    sends.current.set(item.id, {
      timer: setTimeout(() => send(false), 5000),
      send,
      message: `${item.childName} ${dateLabel(item.entryDate)} 요약을 승인했어요`,
    });
    bump();
  }

  function undoApprove() {
    const last = [...sends.current.keys()].pop();
    if (!last) return;
    const entry = sends.current.get(last)!;
    clearTimeout(entry.timer);
    sends.current.delete(last);
    pw.undo(last);
    if (tab === "pending" && !pw.current) pw.open(last);
    bump();
  }
  const latestSend = [...sends.current.values()].pop();

  useEffect(() => {
    const leave = () => [...sends.current.values()].forEach((e) => e.send(true));
    const move = () => [...sends.current.values()].forEach((e) => e.send(false));
    window.addEventListener("pagehide", leave);
    return () => {
      window.removeEventListener("pagehide", leave);
      move();
    };
  }, []);

  function stale(message: string) {
    walk.close();
    setToast(message);
    revalidator.revalidate();
  }

  const pendingLeft = pw.visible.filter((r) => !pw.isDone(r.id)).length;
  const remaking = pw.visible.filter((r) => !pw.isDone(r.id) && isProcessing(r)).length;
  const orgCounts = useRouteLoaderData<typeof orgLayoutLoader>("routes/org/layout");
  const heldLeft = hw.visible.filter((r) => !hw.isDone(r.id)).length;
  const tabs: { key: Tab; label: string; count: number }[] = [
    { key: "pending", label: "전체", count: pendingLeft },
    { key: "hold", label: "보류", count: heldLeft },
  ];
  const current = walk.current;
  const popupProps = { position: walk.position, onClose: walk.close, onDone: finish, onStale: stale, login };

  return (
    <div>
      <WorkHead
        tour="review"
        title={TITLE}
        jobs={[pendingLeft ? `검토 ${pendingLeft}건` : "", heldLeft ? `보류 ${heldLeft}건` : ""].filter(Boolean)}
      />

      <div role="tablist" aria-label="검토 칸" className="mb-4 flex gap-1.5">
        {tabs.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
            className={`tap rounded-full px-5 text-[15px] font-bold ${
              tab === t.key ? "bg-ink text-paper" : "border border-line2 bg-surface text-ink2 hover:bg-surface2"
            }`}
          >
            {t.label} <span className="ml-1 tabular-nums">{t.count}</span>
          </button>
        ))}
      </div>

      {draftsLogin.carried.gone.length > 0 ? (
        <p role="status" className="mb-4 rounded-[14px] bg-humansoft px-4 py-3 text-[14px] text-human">
          로그인하는 사이 {draftsLogin.carried.gone.length}건은 이미 처리돼 고치던 내용을 지웠어요.
        </p>
      ) : null}

      {walk.visible.length === 0 ? (
        <EmptyLine>{tab === "hold" ? "보류한 요약이 없어요" : "검토할 요약이 없어요"}</EmptyLine>
      ) : (
        <>
          <div data-tour="list" className="grid gap-2.5">
            {page.items.map((r) => (
              <WorkRow
                key={r.id}
                who={r.childName}
                sub={dateLabel(r.date)}
                text={
                  r.f
                    ? (r.f.entries[0]?.content ?? "")
                    : r.s!.noContent
                      ? "특이사항이 없어요"
                      : (r.s!.claims[0]?.text ?? r.s!.content)
                }
                tag={r.f ? <Chip tone="bad">요약 실패</Chip> : rowTag(r.s!, tab)}
                action={isProcessing(r) ? undefined : r.f ? "확인하기" : tab === "hold" ? "다시 보기" : "검토하기"}
                onOpen={() => walk.open(r.id)}
                done={walk.isDone(r.id) ? `${r.childName} ${dateLabel(r.date)} · ${outcomes[r.id] ?? "처리했어요"}` : undefined}
              />
            ))}
          </div>
          <Pager {...page} onChange={page.setPage} />
        </>
      )}

      {tab === "pending" && pendingLeft === remaking ? <NextStep approve={orgCounts?.live.counts.approve ?? null} remaking={remaking} /> : null}

      {current?.f ? (
        <FailedPopup key={current.id} rowId={current.id} item={current.f} {...popupProps} />
      ) : current?.s ? (
        tab === "hold" ? (
          <HeldPopup key={current.id} item={current.s} {...popupProps} />
        ) : current.s.noContent ? (
          <NoContentPopup
            key={current.id}
            item={current.s}
            {...popupProps}
            carriedDraft={draftsLogin.carried.drafts[current.id]}
            onCarriedUsed={draftsLogin.used}
          />
        ) : (
          <PendingPopup
            key={current.id}
            item={current.s}
            {...popupProps}
            onApprove={approveLater}
            draft={(() => {
              const saved = drafts.current.get(current.id);
              return saved && saved.version === current.s.version ? saved.draft : undefined;
            })()}
            carriedDraft={draftsLogin.carried.drafts[current.id]}
            loggedOut={draftsLogin.loggedOut}
            onCarriedUsed={draftsLogin.used}
          />
        )
      ) : null}

      <Toast message={toast} onGone={clearToast} />
      <Toast
        slot={0}
        persist
        message={
          latestSend ? (sends.current.size > 1 ? `${latestSend.message} 외 ${sends.current.size - 1}건` : latestSend.message) : null
        }
        onGone={noop}
        action={{ label: "되돌리기", onClick: undoApprove }}
      />
    </div>
  );
}

const noop = () => {};

function NextStep({ approve, remaking }: { approve: number | null; remaking: number }) {
  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-[18px] border border-line px-5 py-4">
      <p className="min-w-0 flex-1 text-[15px] leading-normal text-ink2">
        <b className="text-ink">요약 검토를 다 마쳤어요.</b>{" "}
        {approve == null
          ? "다음은 주간 리포트를 읽고 승인하는 단계예요."
          : approve > 0
            ? `승인을 기다리는 주간 리포트가 ${approve}건 있어요.`
            : "아직 승인할 주간 리포트는 없어요. 한 주가 끝나면 AI가 리포트를 만들어요."}
        {remaking > 0 ? (
          <span className="mt-0.5 block text-[14px] text-muted">AI가 다시 만드는 요약 {remaking}건은 끝나면 여기서 검토할 수 있어요.</span>
        ) : null}
      </p>
      <Link
        to="/children?tab=approve"
        className={`tap inline-flex flex-none items-center gap-0.5 rounded-full px-4 text-[14px] font-bold ${
          approve === 0 ? "border border-line2 text-ink2 hover:bg-surface2" : "bg-ink text-paper hover:brightness-110"
        }`}
      >
        주간 리포트 보러 가기
        <ChevronRight aria-hidden size={15} />
      </Link>
    </div>
  );
}

function rowTag(s: SummaryItem, tab: Tab): React.ReactNode {
  if (s.processing) {
    return (
      <span className="inline-flex items-center gap-1.5 text-[13px] font-bold text-accentink">
        <Spinner size={14} />
        요약 다시 만드는 중
      </span>
    );
  }
  if (tab === "hold") return <Chip tone="idle">보류</Chip>;
  if (s.noContent) return <Chip tone="idle">특이사항 없음</Chip>;
  if (s.needsReview || s.reviewFlags.length > 0) return <Chip tone="human">확인 필요</Chip>;
  return null;
}

interface PopupProps {
  item: SummaryItem;
  position?: { index: number; total: number };
  onClose: () => void;
  onDone: (id: string, outcome: Exclude<Outcome, "stale">) => void;
  onStale: (message: string) => void;
  login: { lost: () => void; reached: () => void };
}

function useDecide(item: SummaryItem, onDone: PopupProps["onDone"], onStale: PopupProps["onStale"], login: PopupProps["login"]) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loginLost, setLoginLost] = useState(false);

  async function run(send: () => Promise<unknown>, outcome: Exclude<Outcome, "stale">) {
    setBusy(true);
    setError(null);
    setLoginLost(false);
    try {
      await send();
      login.reached();
      onDone(item.id, outcome);
    } catch (err) {
      if (isLoginLost(err)) {
        setLoginLost(true);
        setError(messageOf(err));
        login.lost();
        return;
      }
      if (err instanceof ApiError) login.reached();
      if (err instanceof ApiError && (err.code === "SUMMARY_REGENERATING" || err.code === "SUMMARY_CHANGED")) {
        onStale("새 기록이 들어와 요약을 다시 만들고 있어요");
      } else if (isStale(err) || needsReload(err)) {
        onStale(messageOf(err));
      } else {
        setError(messageOf(err));
      }
    } finally {
      setBusy(false);
    }
  }
  return { busy, error, loginLost, run };
}

function popTitle(item: SummaryItem): string {
  return `${item.childName} · ${dateLabel(item.entryDate)}`;
}

function ErrorLine({ error, loginLost = false }: { error: string | null; loginLost?: boolean }) {
  return error ? (
    <span className="flex basis-full flex-wrap items-center justify-end gap-x-3 gap-y-1">
      <span role="alert" className="text-[14px] font-bold text-block">
        {error}
      </span>
      {loginLost ? <ReloginButton role="org" returnTo="/gate1" /> : null}
    </span>
  ) : null;
}

function CarriedNote({ text }: { text: string | null }) {
  return text ? (
    <p role="status" className="rounded-[14px] bg-humansoft px-3.5 py-3 text-[14px] leading-6 text-human">
      {text}
    </p>
  ) : null;
}

function SourceEntries({
  item,
  evidence = null,
}: {
  item: SummaryItem;
  evidence?: { journalEntryId: string; quote: string }[] | null;
}) {
  const uncovered = new Set(item.uncoveredEntries.map((e) => e.journalEntryId));
  const entries = item.entries.length ? item.entries : item.uncoveredEntries;
  const located = evidence && evidence.length ? locateEvidence(entries, evidence) : null;
  const evNote =
    evidence == null ? null : evidence.length === 0 ? (
      "이 문장에는 근거로 든 일지가 없어요. 원본 일지를 직접 읽고 맞는지 확인해 주세요"
    ) : located?.found ? (
      <>
        <mark className="rounded bg-mark/55 px-0.5 text-inherit">노란 부분</mark>이 고른 문장의 근거예요
      </>
    ) : (
      "근거 위치를 못 찾았어요. 원본 일지를 직접 읽어 봐 주세요"
    );
  return (
    <PopSection title="원본 일지">
      {uncovered.size > 0 && !item.noContent ? (
        <p className="-mt-1 mb-2">
          <Chip tone="idle">요약에 안 들어간 일지 {uncovered.size}건 · 승인하면 함께 끝나요</Chip>
        </p>
      ) : null}
      {evNote ? (
        <p role="status" className="mb-1.5 text-[13px] text-ink2">
          {evNote}
        </p>
      ) : null}
      <div className="grid gap-2 rounded-[14px] border border-line bg-paper px-4 py-3.5">
        {entries.length === 0 ? (
          <p className="text-[15px] text-muted">원본 일지를 불러오지 못했어요</p>
        ) : (
          entries.map((e) => (
            <p
              key={e.journalEntryId}
              className={`max-w-[62ch] text-[16px] leading-[1.75] whitespace-pre-wrap ${
                uncovered.has(e.journalEntryId) && !item.noContent ? "text-muted" : ""
              }`}
            >
              {markSegments(e.content, located?.ranges.get(e.journalEntryId) ?? []).map((seg, i) =>
                seg.marked ? (
                  <mark key={i} className="rounded bg-mark/55 px-0.5 text-inherit">
                    {seg.text}
                  </mark>
                ) : (
                  <span key={i}>{seg.text}</span>
                ),
              )}
            </p>
          ))
        )}
      </div>
    </PopSection>
  );
}

interface Draft {
  lines: Line[];
  added: Added[];
}

function PendingPopup({
  item,
  position,
  onClose,
  onDone,
  onStale,
  login,
  onApprove,
  draft,
  carriedDraft,
  loggedOut,
  onCarriedUsed,
}: PopupProps & {
  onApprove: (item: SummaryItem, cards: Gate1Card[] | undefined, draft: Draft) => void;
  draft?: Draft;
  carriedDraft?: Gate1Draft;
  loggedOut: boolean;
  onCarriedUsed: (summaryId: string) => void;
}) {
  const [restored] = useState(() => (draft ? null : restoredOf(item, carriedDraft)));
  const start = draft ?? restored;
  const [lines, setLines] = useState<Line[]>(() => start?.lines ?? freshLines(item.claims));
  const [added, setAdded] = useState<Added[]>(() => start?.added ?? []);
  const [nextKey, setNextKey] = useState(() => (start?.added.length ? Math.max(...start.added.map((a) => a.key)) + 1 : 0));
  const [carriedNote] = useState(() => (carriedDraft && !draft ? (restored ? RESTORED : DROPPED) : null));
  const [confirming, setConfirming] = useState<"hold" | "remove" | null>(null);
  const [ev, setEv] = useState<number | null>(null);
  const { busy, error, loginLost, run } = useDecide(item, onDone, onStale, login);

  useEffect(() => {
    if (carriedDraft) onCarriedUsed(item.id);
  }, [carriedDraft, item.id, onCarriedUsed]);

  useEffect(() => {
    if (!loggedOut) return;
    const touched = lines.some((l) => l.action !== "KEEP") || added.some((a) => a.text.trim());
    saveGate1Draft(item.id, touched ? { version: item.version, lines, added: added.map((a) => a.text) } : null);
  }, [loggedOut, lines, added, item.id, item.version]);

  const edits = lines.filter((l, i) => isEdited(l, item.claims[i])).length;
  const dels = lines.filter((l) => l.action === "DELETE").length;
  const adds = added.filter((a) => a.text.trim()).length;
  const hasChanges = edits + dels + adds > 0;
  const blankEdit = lines.some((l) => l.action === "EDIT" && !l.text.trim());
  const nothingLeft = item.claims.length > 0 && dels === item.claims.length && adds === 0;
  const countText = `수정 ${edits} · 삭제 ${dels} · 추가 ${adds}`;
  const flagged = item.needsReview || item.reviewFlags.length > 0;
  const claimFlags = flagsByClaim(item);
  const uncovered = item.uncoveredEntries.length;

  function setLine(i: number, patch: Partial<Line>) {
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  }

  function decide(decision: "hold" | "remove") {
    void run(() => decideGate1(item.id, { decision, version: item.version }), decision === "hold" ? "held" : "removed").finally(
      () => setConfirming(null),
    );
  }

  const canApprove = !busy && !blankEdit && !nothingLeft;
  function approve() {
    if (!canApprove) return;
    onApprove(item, buildCards(item.claims, lines, added), { lines, added });
  }
  function hold() {
    if (busy) return;
    if (hasChanges) setConfirming("hold");
    else decide("hold");
  }

  const dialog = {
    hold: {
      title: `${item.childName} 요약을 보류할까요?`,
      body: "보류 칸으로 옮겨요. 보류를 풀거나 제거할 수 있어요.",
      warning: hasChanges ? "고치던 문장은 저장되지 않아요" : undefined,
      label: "보류하기",
      tone: "neutral",
    },
    remove: {
      title: `${item.childName} 요약을 제거할까요?`,
      body: "이 요약과 그 일지를 모두 지워요." + (uncovered > 0 ? ` 요약에 안 들어간 일지 ${uncovered}건도 함께 지워져요.` : ""),
      warning: "제거하면 되돌릴 수 없어요",
      label: "제거하기",
      tone: "block",
    },
  } as const;
  const d = confirming ? dialog[confirming] : null;

  return (
    <>
      <ListPopup
        title={popTitle(item)}
        subtitle="문장마다 유지·수정·삭제를 고른 뒤 승인해 주세요"
        icon={<Check aria-hidden size={20} />}
        position={position}
        busy={busy}
        onClose={onClose}
        footer={
          <>
            <PopButton tone="text" onClick={() => setConfirming("remove")} disabled={busy}>
              요약 제거
            </PopButton>
            <span className="flex-1" />
            {hasChanges ? <span className="text-[14px] text-ink2">{countText}</span> : null}
            <PopButton onClick={hold} disabled={busy}>
              보류
            </PopButton>
            <PopButton tone="dark" onClick={approve} disabled={!canApprove}>
              {busy ? <Busy>보내는 중…</Busy> : hasChanges ? "변경 내용으로 승인" : "승인"}
            </PopButton>
            {nothingLeft ? (
              <span className="basis-full text-right text-[14px] text-block">
                남는 문장이 없어요. 요약을 통째로 지우려면 요약 제거를 눌러 주세요.
              </span>
            ) : blankEdit ? (
              <span className="basis-full text-right text-[14px] text-block">고칠 문장이 비어 있어요. 문장을 빼려면 삭제를 눌러 주세요.</span>
            ) : null}
            <ErrorLine error={error} loginLost={loginLost} />
          </>
        }
      >
        <CarriedNote text={carriedNote} />
        {flagged ? (
          <div role="note" className="flex items-start gap-2.5 rounded-[14px] bg-humansoft px-3.5 py-3 text-human">
            <TriangleAlert aria-hidden size={18} strokeWidth={1.7} className="mt-0.5 shrink-0" />
            <div className="grid gap-0.5 text-[14px] leading-6">
              {item.needsReview ? (
                <b className="text-[15px]">
                  {item.reviewReasons.map((r) => REVIEW_REASON[r]).find(Boolean) ?? "공유 전에 한 번 더 확인해 주세요"}
                </b>
              ) : null}
              {item.reviewFlags.length > 0 ? (
                <>
                  <span>검증이 확인을 요청한 원본 일지 {item.reviewFlags.length}건</span>
                  <ul className="grid gap-0.5">
                    {item.reviewFlags.map((f) => (
                      <li key={f.journalEntryId}>
                        <b>{flagPlace(item, f.claimIndexes)}</b>
                        {f.reasons.length > 0 ? ` · ${f.reasons.join(" · ")}` : null}
                      </li>
                    ))}
                  </ul>
                </>
              ) : null}
            </div>
          </div>
        ) : null}

        {item.claims.length === 0 ? (
          <PopSection title="AI 요약">
            <p className="rounded-[14px] bg-surface2/60 p-3 text-[16px] leading-7">{item.content}</p>
            <p className="mt-1.5 text-[14px] text-muted">문장을 나누지 못한 요약이라 그대로 승인하거나 제거해 주세요.</p>
          </PopSection>
        ) : (
          <PopSection title={`AI 요약 ${item.claims.length}문장`}>
            <ol className="grid gap-2">
              {item.claims.map((claim, i) => (
                <SentenceRow
                  key={claim.index}
                  no={i + 1}
                  claim={claim}
                  line={lines[i]}
                  onChange={(patch) => setLine(i, patch)}
                  evOn={ev === i}
                  onEv={() => setEv((cur) => (cur === i ? null : i))}
                  flag={claimFlags.get(claim.index)}
                />
              ))}
              {added.map((a) => (
                <li
                  key={a.key}
                  className="flex flex-wrap items-start gap-3 rounded border border-dashed border-human/50 bg-humansoft/30 py-3 pr-3 pl-3.5"
                >
                  <span aria-hidden className="mt-2.5 flex size-[26px] shrink-0 items-center justify-center rounded-full bg-humansoft text-human">
                    <Plus size={14} strokeWidth={2} />
                  </span>
                  <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-1.5">
                    <textarea
                      aria-label="새 문장"
                      placeholder="일지에 없던 사실을 적어요"
                      rows={2}
                      maxLength={MAX_TEXT}
                      value={a.text}
                      onChange={(e) => setAdded((xs) => xs.map((x) => (x.key === a.key ? { ...x, text: e.target.value } : x)))}
                      className="w-full resize-y rounded border border-human/50 bg-surface px-3 py-2.5 text-[16px] leading-6 outline-none focus:border-human focus:ring-2 focus:ring-human/20"
                    />
                    <span className="self-start rounded bg-humansoft px-2 py-0.5 text-[12px] font-bold text-human">새로 쓴 문장</span>
                  </div>
                  <button
                    type="button"
                    aria-label="새 문장 빼기"
                    onClick={() => setAdded((xs) => xs.filter((x) => x.key !== a.key))}
                    className="min-h-10 shrink-0 rounded border border-line2 bg-surface px-3 text-[14px] font-semibold text-ink2 hover:bg-surface2"
                  >
                    빼기
                  </button>
                </li>
              ))}
            </ol>
            <button
              type="button"
              onClick={() => {
                setAdded((xs) => [...xs, { key: nextKey, text: "" }]);
                setNextKey((k) => k + 1);
              }}
              disabled={item.claims.length + added.length >= MAX_CARDS}
              className="mt-2 inline-flex min-h-9 items-center gap-1 px-1 text-[15px] font-bold text-accentink hover:underline disabled:opacity-60"
            >
              <Plus aria-hidden size={15} strokeWidth={2} />
              문장 추가
            </button>
          </PopSection>
        )}

        <SourceEntries item={item} evidence={ev != null ? (item.claims[ev]?.evidence ?? []) : null} />
      </ListPopup>

      {confirming && d ? (
        <ConfirmDialog
          title={d.title}
          warning={d.warning}
          confirmLabel={d.label}
          tone={d.tone}
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => decide(confirming)}
        >
          {d.body}
        </ConfirmDialog>
      ) : null}
    </>
  );
}

function NoContentPopup({
  item,
  position,
  onClose,
  onDone,
  onStale,
  login,
  carriedDraft,
  onCarriedUsed,
}: PopupProps & { carriedDraft?: Gate1Draft; onCarriedUsed: (summaryId: string) => void }) {
  const [confirming, setConfirming] = useState<"confirm" | "revert" | null>(null);
  const { busy, error, loginLost, run } = useDecide(item, onDone, onStale, login);
  const [carriedNote] = useState(() => (carriedDraft ? DROPPED : null));
  useEffect(() => {
    if (carriedDraft) onCarriedUsed(item.id);
  }, [carriedDraft, item.id, onCarriedUsed]);
  const count = item.entries.length || item.uncoveredEntries.length;

  function decide(decision: "confirm" | "revert") {
    void run(() => decideGate1(item.id, { decision, version: item.version }), decision === "confirm" ? "confirmed" : "reverted").finally(
      () => setConfirming(null),
    );
  }

  return (
    <>
      <ListPopup
        title={popTitle(item)}
        subtitle="AI가 특이사항을 찾지 못했어요"
        icon={<Check aria-hidden size={20} />}
        position={position}
        busy={busy}
        onClose={onClose}
        footer={
          <>
            <PopButton onClick={() => setConfirming("revert")} disabled={busy}>
              다시 요약
            </PopButton>
            <span className="flex-1" />
            <PopButton tone="dark" onClick={() => setConfirming("confirm")} disabled={busy}>
              {busy ? <Busy>보내는 중…</Busy> : "확인"}
            </PopButton>
            <ErrorLine error={error} loginLost={loginLost} />
          </>
        }
      >
        <CarriedNote text={carriedNote} />
        <div className="rounded-[14px] bg-surface2/60 px-4 py-3.5">
          <b className="text-[16px]">이날 기록에는 특이사항이 없어요</b>
          <p className="mt-1 text-[14px] leading-6 text-ink2">
            기록에 따로 알릴 관찰이 없어 요약을 비워 뒀어요. 아래 원문을 읽고 [확인]으로 마무리해 주세요.
          </p>
        </div>
        <SourceEntries item={item} />
      </ListPopup>

      {confirming === "confirm" ? (
        <ConfirmDialog
          title={`${item.childName} 기록을 요약 없이 끝낼까요?`}
          warning="확인하면 되돌릴 수 없어요"
          confirmLabel="확인하기"
          tone="human"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => decide("confirm")}
        >
          이날 기록{count > 0 ? ` ${count}건` : ""}은 "특이사항 없음"으로 끝나고, 아동 타임라인에는 올라가지 않아요.
        </ConfirmDialog>
      ) : confirming === "revert" ? (
        <ConfirmDialog
          title={`${item.childName} 기록을 다시 요약할까요?`}
          confirmLabel="다시 요약"
          tone="neutral"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => decide("revert")}
        >
          이 결과는 목록에서 빠지고, 그 사이 들어온 같은 날 기록과 함께 AI가 새로 요약해요.
        </ConfirmDialog>
      ) : null}
    </>
  );
}

function HeldPopup({ item, position, onClose, onDone, onStale, login }: PopupProps) {
  const [confirming, setConfirming] = useState<"reopen" | "remove" | null>(null);
  const { busy, error, loginLost, run } = useDecide(item, onDone, onStale, login);

  function act(kind: "reopen" | "remove") {
    void run(
      () => (kind === "reopen" ? reopenGate1(item.id) : decideGate1(item.id, { decision: "remove", version: item.version })),
      kind === "reopen" ? "reopened" : "removed",
    ).finally(() => setConfirming(null));
  }

  return (
    <>
      <ListPopup
        title={popTitle(item)}
        subtitle="보류한 요약은 보호자와 다른 기관에 가지 않아요"
        icon={<Check aria-hidden size={20} />}
        position={position}
        busy={busy}
        onClose={onClose}
        footer={
          <>
            <PopButton tone="text" onClick={() => setConfirming("remove")} disabled={busy}>
              요약 제거
            </PopButton>
            <span className="flex-1" />
            <PopButton tone="dark" onClick={() => setConfirming("reopen")} disabled={busy}>
              {busy ? <Busy>보내는 중…</Busy> : "보류 풀기"}
            </PopButton>
            <ErrorLine error={error} loginLost={loginLost} />
          </>
        }
      >
        <PopSection title={item.claims.length ? `AI 요약 ${item.claims.length}문장` : "AI 요약"}>
          {item.claims.length === 0 ? (
            <p className="rounded-[14px] bg-surface2/60 p-3 text-[16px] leading-7">{item.content}</p>
          ) : (
            <ol className="grid gap-2">
              {item.claims.map((c, i) => (
                <li key={c.index} className="flex items-start gap-3 rounded border border-line bg-surface px-3.5 py-3">
                  <NumberDot n={i + 1} className="bg-surface2 text-ink2" />
                  <span className="pt-0.5 text-[16px] leading-6 text-ink2">{c.text}</span>
                </li>
              ))}
            </ol>
          )}
        </PopSection>
        <SourceEntries item={item} />
      </ListPopup>

      {confirming === "reopen" ? (
        <ConfirmDialog
          title={`${item.childName} 요약의 보류를 풀까요?`}
          confirmLabel="보류 풀기"
          tone="accent"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => act("reopen")}
        >
          AI 요약 그대로 전체 칸으로 돌아가요.
        </ConfirmDialog>
      ) : confirming === "remove" ? (
        <ConfirmDialog
          title={`${item.childName} 요약을 제거할까요?`}
          warning="제거하면 되돌릴 수 없어요"
          confirmLabel="제거하기"
          tone="block"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => act("remove")}
        >
          이 요약과 그 일지를 모두 지워요.
        </ConfirmDialog>
      ) : null}
    </>
  );
}

const FAIL_REASON: Record<string, string> = {
  NO_GROUNDED_CLAIMS: "요약 문장의 근거를 원문에서 찾지 못했어요.",
};

function FailedPopup({
  rowId,
  item,
  position,
  onClose,
  onDone,
  onStale,
}: Omit<PopupProps, "item"> & { rowId: string; item: FailedSummary }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loginLost, setLoginLost] = useState(false);
  const [confirming, setConfirming] = useState(false);

  async function run(kind: "retry" | "close") {
    setBusy(true);
    setError(null);
    try {
      if (kind === "retry") await retryFailedSummary(item.childId, item.entryDate);
      else await closeFailedSummary(item.childId, item.entryDate);
      setConfirming(false);
      onDone(rowId, kind === "retry" ? "retried" : "closed");
    } catch (err) {
      setConfirming(false);
      setLoginLost(isLoginLost(err));
      if (err instanceof ApiError && err.status === 404) onStale("이미 처리했거나 볼 수 없는 기록이에요. 목록을 새로 불러왔어요.");
      else setError(messageOf(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <ListPopup
        title={`${item.childName} · ${dateLabel(item.entryDate)}`}
        subtitle="요약 실패 · 선생님이 정해 주세요"
        icon={<TriangleAlert aria-hidden size={20} />}
        position={position}
        busy={busy}
        onClose={onClose}
        footer={
          <>
            <PopButton onClick={() => setConfirming(true)} disabled={busy}>
              요약 없이 마무리
            </PopButton>
            <span className="flex-1" />
            <PopButton tone="dark" onClick={() => run("retry")} disabled={busy}>
              {busy ? <Busy>보내는 중…</Busy> : "다시 요약"}
            </PopButton>
            <ErrorLine error={error} loginLost={loginLost} />
          </>
        }
      >
        <div className="rounded-[14px] bg-humansoft px-3.5 py-3 text-[14px] leading-normal text-human">
          <b>AI가 요약을 마치지 못했어요.</b> {FAIL_REASON[item.reason] ?? "원본 일지를 보고 정해 주세요."}
        </div>
        <PopSection title={`원본 일지 ${item.entries.length}건`}>
          <div className="grid gap-2 rounded-[14px] border border-line bg-paper px-4 py-3.5">
            {item.entries.map((e) => (
              <p key={e.journalEntryId} className="max-w-[62ch] text-[16px] leading-[1.75] whitespace-pre-wrap">
                {e.content}
              </p>
            ))}
          </div>
        </PopSection>
      </ListPopup>

      {confirming ? (
        <ConfirmDialog
          title={`${item.childName} 기록을 요약 없이 끝낼까요?`}
          warning="되돌릴 수 없어요"
          confirmLabel="요약 없이 마무리"
          tone="human"
          busy={busy}
          onCancel={() => setConfirming(false)}
          onConfirm={() => run("close")}
        >
          이날 기록 {item.entries.length}건은 보호자·아동 기록·주간 리포트로 가지 않아요.
        </ConfirmDialog>
      ) : null}
    </>
  );
}
