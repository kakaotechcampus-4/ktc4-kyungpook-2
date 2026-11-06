import { useCallback, useEffect, useState } from "react";
import { useLoaderData, useRevalidator, useSearchParams } from "react-router";
import { Check, Pencil, Plus, Trash2, TriangleAlert } from "lucide-react";
import { NotReadyPage } from "@/components/org/NotReady";
import { Busy, ConfirmDialog, Spinner } from "@/components/ui";
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
import { ApiError, decideGate1, getGate1Queue, isReady, reopenGate1 } from "@/lib/api";
import type { Gate1Card, SummaryClaim, SummaryItem } from "@/lib/types";

/**
 * 검토 — 요약을 읽고 승인한다 (O-30 · O-31 · O-33).
 * 목록은 한 줄 카드, 누르면 팝업에서 문장마다 유지·수정·삭제를 고르고 원본 일지를 함께 본다.
 * 처리하면 같은 칸의 다음 요약이 이어서 열린다. 칸은 [전체](검토 대기) · [보류].
 * 대시보드 바로가기: `?open=first` (첫 건), `?open=summary&id=` (그 요약), `?tab=hold` (보류 칸).
 */
export async function clientLoader() {
  // BE 는 상태 하나씩만 준다 — 검토 대기와 보류를 함께 묻는다
  const [pending, held] = await Promise.all([getGate1Queue("pending"), getGate1Queue("hold")]);
  return { pending, held };
}

const TITLE = "검토";

type Tab = "pending" | "hold";
type Decision = "approve" | "hold" | "remove";
/** 이번 방문에서 처리한 결과. stale: 그새 다른 곳에서 처리돼 더 결정할 수 없다 */
type Outcome =
  | "approved"
  | "corrected"
  | "held"
  | "removed"
  | "reopened"
  | "confirmed"
  | "reverted"
  | "stale";

/** 문장 하나에 고른 처리. text 는 EDIT 일 때 고치는 글 */
interface Line {
  action: "KEEP" | "EDIT" | "DELETE";
  text: string;
}

interface Added {
  key: number;
  text: string;
}

/** BE 가 문장 카드를 100장까지 받는다 (Gate1DecisionRequest.MAX_CARDS) */
const MAX_CARDS = 100;
const MAX_TEXT = 1000;

const WEEKDAY = "일월화수목금토";
function dateLabel(ymd: string): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return `${m}월 ${d}일 (${WEEKDAY[new Date(y, m - 1, d).getDay()]})`;
}

/** reviewReasons 원본 → 경고 제목 */
const REVIEW_REASON: Record<string, string> = {
  다른아동이름: "다른 아이 이름이 들어 있을 수 있어요",
};

function freshLines(claims: SummaryClaim[]): Line[] {
  return claims.map(() => ({ action: "KEEP", text: "" }));
}

/** 줄바꿈·앞뒤 공백만 다르면 BE 도 고치지 않은 것(KEEP)으로 본다 */
function isEdited(line: Line, claim: SummaryClaim): boolean {
  const text = line.text.trim();
  return line.action === "EDIT" && text !== "" && text !== claim.text.trim();
}

/**
 * 원래 문장은 모두 한 번씩, 순서대로 보내고 새 문장은 뒤에 붙인다.
 * 바꾼 것이 없으면 undefined — cards 없이 보내야 AI 요약 그대로 승인(APPROVED)이 된다.
 */
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

/** 다시 눌러도 결과가 같은 오류 — 카드를 결정할 수 없는 상태로 바꾼다 */
function isStale(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    (err.code === "SUMMARY_ALREADY_REVIEWED" ||
      err.code === "SUMMARY_HELD" ||
      err.code === "SUMMARY_NOT_HELD" ||
      err.status === 404)
  );
}

/** 목록을 새로 받으면 풀리는 오류 — 카드는 남기고 다시 불러온다 */
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

function SentenceRow({
  no,
  claim,
  line,
  onChange,
}: {
  no: number;
  claim: SummaryClaim;
  line: Line;
  onChange: (patch: Partial<Line>) => void;
}) {
  const isEdit = line.action === "EDIT";
  const isDelete = line.action === "DELETE";
  const changed = isEdited(line, claim);

  const row = isDelete
    ? "border-line bg-paper"
    : isEdit
      ? "border-accent/35 bg-accentsoft/35"
      : "border-line bg-surface";
  const dot = isEdit
    ? "bg-ink text-paper"
    : isDelete
      ? "bg-blocksoft text-block"
      : "bg-surface2 text-ink2";

  return (
    <li className={`flex flex-wrap items-start gap-3 rounded border py-3 pr-3 pl-3.5 ${row}`}>
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
                  // 수정을 처음 누르면 원문을 채워 두고, 다시 고르면 고치던 글을 살린다
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

/* ── 화면 ─────────────────────────────────────────── */

const idOf = (s: SummaryItem) => s.id;
const isProcessing = (s: SummaryItem) => s.processing;

/** 처리한 결과 한 줄 (목록 카드가 ✓ 로 바뀌는 2초 동안) */
const OUTCOME: Record<Exclude<Outcome, "stale">, string> = {
  approved: "AI 요약 그대로 승인했어요",
  corrected: "고친 내용으로 승인했어요",
  held: "보류로 옮겼어요",
  removed: "요약을 제거했어요",
  reopened: "보류를 풀었어요 · 전체 칸에서 다시 볼 수 있어요",
  confirmed: "요약 없이 마무리했어요",
  reverted: "다시 요약하도록 돌렸어요",
};

/** 백엔드에 아직 없으면 mock 데이터 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function Gate1Route() {
  if (!isReady("gate1")) return <NotReadyPage title={TITLE} />;
  return <Gate1Page />;
}

function Gate1Page() {
  const { pending, held } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [params, setParams] = useSearchParams();
  const tab: Tab = params.get("tab") === "hold" ? "hold" : "pending";
  const [toast, setToast] = useState<string | null>(null);
  const clearToast = useCallback(() => setToast(null), []);
  /** 처리한 요약 id → 결과 문구. ✓ 줄에 쓴다 */
  const [outcomes, setOutcomes] = useState<Record<string, string>>({});

  const pw = useWalk(pending, idOf, isProcessing);
  const hw = useWalk(held, idOf);
  const walk = tab === "hold" ? hw : pw;
  const page = usePage(walk.visible);

  function setTab(next: Tab) {
    setParams(next === "hold" ? { tab: "hold" } : {}, { replace: true, preventScrollReset: true });
  }

  // 대시보드 바로가기: ?open=first | summary&id= → 그 요약 팝업을 연 채로. 주소에서는 지운다
  useEffect(() => {
    const open = params.get("open");
    if (!open) return;
    const list = (tab === "hold" ? held : pending).filter((s) => !s.processing);
    const id = params.get("id");
    // id 를 줬는데 없으면(그새 처리됨) 다른 요약으로 대신 열지 않는다
    const target = open === "summary" && id ? list.find((s) => s.id === id) : open === "first" ? list[0] : undefined;
    if (target) walk.open(target.id);
    setParams(tab === "hold" ? { tab: "hold" } : {}, { replace: true });
  }, [params]);

  function finish(id: string, outcome: Exclude<Outcome, "stale">) {
    setOutcomes((o) => ({ ...o, [id]: OUTCOME[outcome] }));
    walk.done(id);
    // ✓ 를 2초 보인 뒤 목록·메뉴 배지를 서버와 맞춘다
    setTimeout(() => revalidator.revalidate(), 2100);
  }

  /** 그새 요약이 바뀌었거나 이미 처리된 건 — 팝업을 닫고 알린 뒤 목록을 새로 받는다 */
  function stale(message: string) {
    walk.close();
    setToast(message);
    revalidator.revalidate();
  }

  const pendingLeft = pw.visible.filter((s) => !pw.isDone(s.id)).length;
  const heldLeft = hw.visible.filter((s) => !hw.isDone(s.id)).length;
  const tabs: { key: Tab; label: string; count: number }[] = [
    { key: "pending", label: "전체", count: pendingLeft },
    { key: "hold", label: "보류", count: heldLeft },
  ];
  const current = walk.current;

  return (
    <div>
      <WorkHead
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

      {walk.visible.length === 0 ? (
        <EmptyLine>{tab === "hold" ? "보류한 요약이 없어요" : "검토할 요약이 없어요"}</EmptyLine>
      ) : (
        <>
          <div className="grid gap-2.5">
            {page.items.map((s) => (
              <WorkRow
                key={s.id}
                who={s.childName}
                sub={dateLabel(s.entryDate)}
                text={s.noContent ? "요약할 내용을 찾지 못했어요" : (s.claims[0]?.text ?? s.content)}
                tag={rowTag(s, tab)}
                action={s.processing ? undefined : tab === "hold" ? "다시 보기" : "검토하기"}
                onOpen={() => walk.open(s.id)}
                done={walk.isDone(s.id) ? `${s.childName} ${dateLabel(s.entryDate)} · ${outcomes[s.id] ?? "처리했어요"}` : undefined}
              />
            ))}
          </div>
          <Pager {...page} onChange={page.setPage} />
        </>
      )}

      {current ? (
        tab === "hold" ? (
          <HeldPopup key={current.id} item={current} position={walk.position} onClose={walk.close} onDone={finish} onStale={stale} />
        ) : current.noContent ? (
          <NoContentPopup key={current.id} item={current} position={walk.position} onClose={walk.close} onDone={finish} onStale={stale} />
        ) : (
          <PendingPopup key={current.id} item={current} position={walk.position} onClose={walk.close} onDone={finish} onStale={stale} />
        )
      ) : null}

      <Toast message={toast} onGone={clearToast} />
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
  if (s.noContent) return <Chip tone="idle">요약할 내용 없음</Chip>;
  if (s.needsReview || s.reviewFlags.length > 0) return <Chip tone="human">확인 필요</Chip>;
  return null;
}

/* ── 팝업 공통 ─────────────────────────────────────── */

interface PopupProps {
  item: SummaryItem;
  position?: { index: number; total: number };
  onClose: () => void;
  onDone: (id: string, outcome: Exclude<Outcome, "stale">) => void;
  onStale: (message: string) => void;
}

/**
 * 결정 하나를 보내고 결과를 나눈다. 그새 바뀐 요약(409 SUMMARY_CHANGED · SUMMARY_REGENERATING)과 이미 처리된 요약은
 * 팝업을 닫고 알린다. 나머지 오류는 팝업 안에 보인다.
 */
function useDecide(item: SummaryItem, onDone: PopupProps["onDone"], onStale: PopupProps["onStale"]) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(send: () => Promise<unknown>, outcome: Exclude<Outcome, "stale">) {
    setBusy(true);
    setError(null);
    try {
      await send();
      onDone(item.id, outcome);
    } catch (err) {
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
  return { busy, error, run };
}

function popTitle(item: SummaryItem): string {
  return `${item.childName} · ${dateLabel(item.entryDate)}`;
}

function ErrorLine({ error }: { error: string | null }) {
  return error ? (
    <span role="alert" className="basis-full text-[14px] font-bold text-block">
      {error}
    </span>
  ) : null;
}

/** 원본 일지. 요약에 안 들어간 일지는 흐리게 두고 머리에 건수를 보인다 */
function SourceEntries({ item }: { item: SummaryItem }) {
  const uncovered = new Set(item.uncoveredEntries.map((e) => e.journalEntryId));
  const entries = item.entries.length ? item.entries : item.uncoveredEntries;
  return (
    <PopSection title="원본 일지">
      {uncovered.size > 0 && !item.noContent ? (
        <p className="-mt-1 mb-2">
          <Chip tone="idle">요약에 안 들어간 일지 {uncovered.size}건 · 승인하면 함께 끝나요</Chip>
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
              {e.content}
            </p>
          ))
        )}
      </div>
    </PopSection>
  );
}

/* ── 검토 대기 팝업 ─────────────────────────────────── */

function PendingPopup({ item, position, onClose, onDone, onStale }: PopupProps) {
  const [lines, setLines] = useState<Line[]>(() => freshLines(item.claims));
  const [added, setAdded] = useState<Added[]>([]);
  const [nextKey, setNextKey] = useState(0);
  const [confirming, setConfirming] = useState<Decision | null>(null);
  const { busy, error, run } = useDecide(item, onDone, onStale);

  const edits = lines.filter((l, i) => isEdited(l, item.claims[i])).length;
  const dels = lines.filter((l) => l.action === "DELETE").length;
  const adds = added.filter((a) => a.text.trim()).length;
  const hasChanges = edits + dels + adds > 0;
  const blankEdit = lines.some((l) => l.action === "EDIT" && !l.text.trim());
  const nothingLeft = item.claims.length > 0 && dels === item.claims.length && adds === 0;
  const countText = `수정 ${edits} · 삭제 ${dels} · 추가 ${adds}`;
  const flagged = item.needsReview || item.reviewFlags.length > 0;
  const flagReasons = [...new Set(item.reviewFlags.flatMap((f) => f.reasons))];
  const uncovered = item.uncoveredEntries.length;

  function setLine(i: number, patch: Partial<Line>) {
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  }

  function decide(decision: Decision) {
    const cards = decision === "approve" ? buildCards(item.claims, lines, added) : undefined;
    const outcome = decision === "approve" ? (cards ? "corrected" : "approved") : decision === "hold" ? "held" : "removed";
    void run(
      () => decideGate1(item.id, { decision, version: item.version, ...(cards ? { cards } : {}) }),
      outcome,
    ).finally(() => setConfirming(null));
  }

  const dialog = {
    approve: {
      title: `${item.childName} 요약을 승인할까요?`,
      body:
        (hasChanges ? `고친 내용으로 승인해요 (${countText}).` : "AI 요약 그대로 승인해요.") +
        (uncovered > 0 ? ` 요약에 안 들어간 일지 ${uncovered}건도 함께 끝나요.` : ""),
      warning: "승인은 되돌릴 수 없어요",
      label: hasChanges ? "변경 내용으로 승인" : "승인하기",
      tone: "human",
    },
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
            <PopButton onClick={() => setConfirming("hold")} disabled={busy}>
              보류
            </PopButton>
            <PopButton tone="dark" onClick={() => setConfirming("approve")} disabled={busy || blankEdit || nothingLeft}>
              {busy ? <Busy>보내는 중…</Busy> : hasChanges ? "변경 내용으로 승인" : "승인"}
            </PopButton>
            {nothingLeft ? (
              <span className="basis-full text-right text-[14px] text-block">
                남는 문장이 없어요. 요약을 통째로 지우려면 요약 제거를 눌러 주세요.
              </span>
            ) : blankEdit ? (
              <span className="basis-full text-right text-[14px] text-block">고칠 문장이 비어 있어요. 문장을 빼려면 삭제를 눌러 주세요.</span>
            ) : null}
            <ErrorLine error={error} />
          </>
        }
      >
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
                <span>
                  검증이 확인을 요청한 원본 일지 {item.reviewFlags.length}건
                  {flagReasons.length > 0 ? ` · ${flagReasons.join(" · ")}` : null}
                </span>
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
                <SentenceRow key={claim.index} no={i + 1} claim={claim} line={lines[i]} onChange={(patch) => setLine(i, patch)} />
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

        <SourceEntries item={item} />
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

/* ── 요약할 내용 없음 팝업 (#244) ─────────────────────── */

function NoContentPopup({ item, position, onClose, onDone, onStale }: PopupProps) {
  const [confirming, setConfirming] = useState<"confirm" | "revert" | null>(null);
  const { busy, error, run } = useDecide(item, onDone, onStale);
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
        subtitle="AI가 요약할 내용을 찾지 못했어요"
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
            <ErrorLine error={error} />
          </>
        }
      >
        <div className="rounded-[14px] bg-surface2/60 px-4 py-3.5">
          <b className="text-[16px]">이날 기록에서 요약할 내용을 찾지 못했어요</b>
          <p className="mt-1 text-[14px] leading-6 text-ink2">
            AI가 내용을 지어내지 않도록 요약을 비워 뒀어요. 원문을 읽고 [확인]으로 마무리하거나, [다시 요약]으로 다시 요약할 수 있게
            해 주세요.
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
          이날 기록{count > 0 ? ` ${count}건` : ""}은 "요약할 내용 없음"으로 끝나고, 아동 타임라인에는 올라가지 않아요.
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

/* ── 보류 팝업 ─────────────────────────────────────── */

function HeldPopup({ item, position, onClose, onDone, onStale }: PopupProps) {
  const [confirming, setConfirming] = useState<"reopen" | "remove" | null>(null);
  const { busy, error, run } = useDecide(item, onDone, onStale);

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
            <ErrorLine error={error} />
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
