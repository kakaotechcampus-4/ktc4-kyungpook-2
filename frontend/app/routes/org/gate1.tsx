import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import {
  Check,
  ChevronRight,
  LoaderCircle,
  Pencil,
  Plus,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import { NotReadyPage } from "@/components/org/NotReady";
import { Card, ConfirmDialog, EmptyState, PageHeader } from "@/components/ui";
import { ApiError, decideGate1, getGate1Queue, isReady, reopenGate1 } from "@/lib/api";
import type { Gate1Card, SummaryClaim, SummaryItem } from "@/lib/types";

/** BE 는 상태 하나씩만 준다. 검토 대기와 보류함을 함께 묻는다 */
export async function clientLoader() {
  const [pending, held] = await Promise.all([getGate1Queue("pending"), getGate1Queue("hold")]);
  return { pending, held };
}

const TITLE = "1차 검토 · 요약 확인";
const DESCRIPTION = "AI 요약이 기록과 맞는지 문장마다 확인하고 승인해요";

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

/* ── 공통 조각 ───────────────────────────────────────── */

function ItemHeader({ item, badge }: { item: SummaryItem; badge?: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-2.5 border-b border-surface2 pb-3.5">
      <span
        aria-hidden
        className="flex size-[34px] items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
      >
        {item.childName.slice(0, 1)}
      </span>
      <span className="text-[17px] font-bold">{item.childName}</span>
      <span className="text-[14px] text-muted">
        {dateLabel(item.entryDate)} · {item.revision}회차 요약
      </span>
      {badge ? <span className="ml-auto">{badge}</span> : null}
    </div>
  );
}

function Pill({ tone, children }: { tone: "review" | "accent" | "neutral"; children: React.ReactNode }) {
  const cls = {
    review: "bg-reviewsoft text-review",
    accent: "bg-accentsoft text-accentink",
    neutral: "bg-surface2 text-ink2",
  }[tone];
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-[13px] font-bold ${cls}`}>{children}</span>
  );
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

/** 고칠 수 없는 상태(처리 중 · 보류함)에서 문장을 그대로 보인다 */
function ReadOnlySentences({ item, dim = false }: { item: SummaryItem; dim?: boolean }) {
  if (item.claims.length === 0) {
    return (
      <p className={`rounded bg-surface2/60 p-3 text-[16px] leading-7 ${dim ? "opacity-55" : ""}`}>
        {item.content}
      </p>
    );
  }
  return (
    <ol className={`flex flex-col gap-2 ${dim ? "opacity-55" : ""}`}>
      {item.claims.map((c, i) => (
        <li
          key={c.index}
          className="flex items-start gap-3 rounded border border-line bg-surface px-3.5 py-3"
        >
          <NumberDot n={i + 1} className="bg-surface2 text-ink2" />
          <span className="pt-0.5 text-[16px] leading-6 text-ink2">{c.text}</span>
        </li>
      ))}
    </ol>
  );
}

function UncoveredEntries({ item }: { item: SummaryItem }) {
  if (item.uncoveredEntries.length === 0) return null;
  return (
    <details className="group rounded border border-line bg-paper/60">
      <summary className="tap flex cursor-pointer list-none items-center gap-2 px-3.5 text-[14px] text-ink2 [&::-webkit-details-marker]:hidden">
        <ChevronRight
          aria-hidden
          size={15}
          strokeWidth={2}
          className="transition-transform group-open:rotate-90"
        />
        <b className="font-bold">요약에 안 들어간 일지 {item.uncoveredEntries.length}건</b>
        <span className="text-muted">· 승인하면 함께 끝나요</span>
      </summary>
      <ul className="flex flex-col gap-2 px-3.5 pb-3.5 pl-9">
        {item.uncoveredEntries.map((e) => (
          <li key={e.journalEntryId} className="text-[15px] leading-6 text-ink2">
            “{e.content}”
          </li>
        ))}
      </ul>
    </details>
  );
}

/* ── 검토 대기 카드 ─────────────────────────────────── */

function PendingItem({
  item,
  onResolved,
}: {
  item: SummaryItem;
  onResolved: (outcome: Outcome, message?: string) => void;
}) {
  const revalidator = useRevalidator();
  const [lines, setLines] = useState<Line[]>(() => freshLines(item.claims));
  const [added, setAdded] = useState<Added[]>([]);
  const [nextKey, setNextKey] = useState(0);
  const [seenVersion, setSeenVersion] = useState(item.version);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<Decision | null>(null);
  const [busy, setBusy] = useState(false);

  /*
   * 목록을 다시 받았는데 요약이 바뀌었으면 고치던 내용을 버린다. 옛 문장 번호로 보내면
   * 엉뚱한 문장이 고쳐지거나 400 이 난다. (렌더 중 상태 조정 — React 문서의 권장 방식)
   * 아래 계산보다 먼저 해야 한다 — 옛 lines 와 새 claims 는 길이가 달라 짝을 지으면 화면이 깨진다.
   * 열려 있던 확인 창도 닫는다. 안 닫으면 새 문장을 읽지 않고 승인하게 된다.
   */
  if (seenVersion !== item.version) {
    const touched = lines.some((l) => l.action !== "KEEP") || added.some((a) => a.text.trim());
    setSeenVersion(item.version);
    setLines(freshLines(item.claims));
    setAdded([]);
    setConfirming(null);
    setError(null);
    setNotice(
      "요약이 새 기록으로 바뀌었어요. 바뀐 문장을 다시 읽고 결정해 주세요." +
        (touched ? " 고치던 내용은 지웠어요." : ""),
    );
    // 이 렌더 결과는 React 가 버리고 새 상태로 바로 다시 그린다
    return null;
  }

  const edits = lines.filter((l, i) => isEdited(l, item.claims[i])).length;
  const dels = lines.filter((l) => l.action === "DELETE").length;
  const adds = added.filter((a) => a.text.trim()).length;
  const hasChanges = edits + dels + adds > 0;

  const canEdit = item.claims.length > 0 && !item.processing;
  const blankEdit = lines.some((l) => l.action === "EDIT" && !l.text.trim());
  const nothingLeft = item.claims.length > 0 && dels === item.claims.length && adds === 0;
  const approveBlocked = item.processing || blankEdit || nothingLeft;
  const countText = `수정 ${edits} · 삭제 ${dels} · 추가 ${adds}`;
  const flagged = item.needsReview || item.reviewFlags.length > 0;

  function setLine(i: number, patch: Partial<Line>) {
    setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  }

  function cancelEdits() {
    setLines(freshLines(item.claims));
    setAdded([]);
  }

  async function decide(decision: Decision) {
    const cards = decision === "approve" ? buildCards(item.claims, lines, added) : undefined;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await decideGate1(item.id, { decision, version: item.version, ...(cards ? { cards } : {}) });
      onResolved(
        decision === "approve" ? (cards ? "corrected" : "approved") : decision === "hold" ? "held" : "removed",
      );
      await revalidator.revalidate();
    } catch (err) {
      if (isStale(err)) {
        onResolved("stale", messageOf(err));
        await revalidator.revalidate();
      } else {
        setError(messageOf(err));
        if (needsReload(err)) await revalidator.revalidate();
      }
    } finally {
      setBusy(false);
      setConfirming(null);
    }
  }

  const uncovered = item.uncoveredEntries.length;
  const dialog = {
    approve: {
      title: `${item.childName} 요약을 승인할까요?`,
      body:
        (hasChanges ? `고친 내용으로 승인해요 (${countText}).` : "AI 요약 그대로 승인해요.") +
        (uncovered > 0 ? ` 요약에 안 들어간 일지 ${uncovered}건도 함께 끝나요.` : ""),
      warning: "승인하면 되돌릴 수 없어요.",
      label: hasChanges ? "변경 내용으로 승인" : "승인하기",
      tone: "human",
    },
    hold: {
      title: `${item.childName} 요약을 보류할까요?`,
      body: "보류함으로 옮겨요. 보류함에서 해제하거나 제거할 수 있어요.",
      warning: hasChanges ? "고치던 문장은 저장되지 않아요." : undefined,
      label: "보류하기",
      tone: "neutral",
    },
    remove: {
      title: `${item.childName} 요약을 제거할까요?`,
      body:
        "이 요약과 그 일지를 모두 지워요." +
        (uncovered > 0 ? ` 요약에 안 들어간 일지 ${uncovered}건도 함께 지워져요.` : ""),
      warning: "제거하면 되돌릴 수 없어요.",
      label: "제거하기",
      tone: "block",
    },
  } as const;
  const d = confirming ? dialog[confirming] : null;

  return (
    <article
      aria-label={`${item.childName} ${dateLabel(item.entryDate)} 요약`}
      className="flex flex-col gap-4 rounded border border-line bg-surface px-6 pt-5.5 pb-5"
    >
      <ItemHeader
        item={item}
        badge={
          item.processing ? (
            <Pill tone="accent">요약 다시 만드는 중</Pill>
          ) : flagged ? (
            <Pill tone="review">확인 필요</Pill>
          ) : null
        }
      />

      {item.processing ? (
        <div
          role="status"
          className="flex items-center gap-2.5 rounded bg-accentsoft px-3.5 py-3 text-accentink"
        >
          <LoaderCircle aria-hidden size={18} strokeWidth={1.8} className="shrink-0" />
          <p className="text-[14px] leading-6">
            <b className="font-bold">새 기록을 반영하는 중이에요.</b> 요약이 곧 바뀌어서 지금은
            고치거나 승인할 수 없어요.
          </p>
        </div>
      ) : null}

      {notice ? (
        <p role="status" className="rounded bg-reviewsoft px-3.5 py-3 text-[14px] leading-6 text-review">
          {notice}
        </p>
      ) : null}

      {/* 막지 않고 경고만 한다. 어느 단어인지는 BE 가 주지 않아 본문 강조는 하지 않는다 */}
      {flagged && !item.processing ? (
        <div role="note" className="flex items-start gap-2.5 rounded bg-reviewsoft px-3.5 py-3 text-review">
          <TriangleAlert aria-hidden size={18} strokeWidth={1.7} className="mt-0.5 shrink-0" />
          <div className="flex flex-col gap-0.5">
            {item.needsReview ? (
              <>
                <p className="text-[15px] font-bold">
                  {item.reviewReasons.map((r) => REVIEW_REASON[r]).find(Boolean) ??
                    "공유 전에 한 번 더 확인해 주세요"}
                </p>
                <p className="text-[14px] leading-6">
                  보호자와 다른 기관에 가기 전에 문장을 읽고, 해당 문장을 고치거나 삭제해 주세요.
                </p>
              </>
            ) : null}
            {item.reviewFlags.length > 0 ? (
              <p className="text-[14px] leading-6">
                검증이 확인을 요청한 원본 일지 {item.reviewFlags.length}건 ·{" "}
                {[...new Set(item.reviewFlags.flatMap((f) => f.issueTypes))].join(", ")}
              </p>
            ) : null}
          </div>
        </div>
      ) : null}

      {item.processing ? (
        <ReadOnlySentences item={item} dim />
      ) : item.claims.length === 0 ? (
        <div className="flex flex-col gap-2.5">
          <h2 className="text-[15px] font-bold text-ink2">AI 요약</h2>
          <p className="rounded bg-surface2/60 p-3 text-[16px] leading-7">{item.content}</p>
          <p className="text-[14px] text-muted">
            문장을 나누지 못한 요약이라 문장별로 고칠 수 없어요. 그대로 승인하거나 제거해 주세요.
          </p>
        </div>
      ) : (
        <div className="flex flex-col gap-2.5">
          <div className="flex flex-wrap items-baseline justify-between gap-1.5">
            <h2 className="text-[15px] font-bold text-ink2">AI 요약 · 문장 {item.claims.length}개</h2>
            <p className="text-[13px] text-muted">
              문장마다 고른 뒤, 아래 승인을 누르면 한 번에 반영돼요
            </p>
          </div>

          <ol className="flex flex-col gap-2">
            {item.claims.map((claim, i) => (
              <SentenceRow
                key={claim.index}
                no={i + 1}
                claim={claim}
                line={lines[i]}
                onChange={(patch) => setLine(i, patch)}
              />
            ))}
            {added.map((a) => (
              <li
                key={a.key}
                className="flex flex-wrap items-start gap-3 rounded border border-dashed border-human/50 bg-humansoft/30 py-3 pr-3 pl-3.5"
              >
                <span
                  aria-hidden
                  className="mt-2.5 flex size-[26px] shrink-0 items-center justify-center rounded-full bg-humansoft text-human"
                >
                  <Plus size={14} strokeWidth={2} />
                </span>
                <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-1.5">
                  <textarea
                    aria-label="새 문장"
                    placeholder="일지에 없던 사실을 적어요"
                    rows={2}
                    maxLength={MAX_TEXT}
                    value={a.text}
                    onChange={(e) =>
                      setAdded((xs) =>
                        xs.map((x) => (x.key === a.key ? { ...x, text: e.target.value } : x)),
                      )
                    }
                    className="w-full resize-y rounded border border-human/50 bg-surface px-3 py-2.5 text-[16px] leading-6 outline-none focus:border-human focus:ring-2 focus:ring-human/20"
                  />
                  <p className="flex flex-wrap items-center gap-2 text-[13px] text-muted">
                    <span className="rounded bg-humansoft px-2 py-0.5 text-[12px] font-bold text-human">
                      새로 쓴 문장
                    </span>
                    근거 일지 없음 · 검증을 거치지 않아요
                  </p>
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
            className="tap flex items-center justify-center gap-1.5 rounded border border-dashed border-line2 bg-surface text-[15px] font-semibold text-accentink hover:bg-surface2 disabled:opacity-60"
          >
            <Plus aria-hidden size={15} strokeWidth={2} />
            문장 추가
          </button>
        </div>
      )}

      <UncoveredEntries item={item} />

      <div className="flex flex-col gap-1.5 border-t border-surface2 pt-3.5">
        <div className="flex flex-wrap items-center gap-2.5">
          {canEdit ? (
            <>
              <p aria-live="polite" className="text-[14px] text-ink2">
                {hasChanges ? countText : "고친 문장 없음"}
              </p>
              {hasChanges ? (
                <button
                  type="button"
                  onClick={cancelEdits}
                  className="tap px-2 text-[14px] font-semibold text-accentink underline"
                >
                  편집 취소
                </button>
              ) : null}
            </>
          ) : null}
          <div className="ml-auto flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => setConfirming("hold")}
              disabled={busy || item.processing}
              className="tap rounded border border-line2 bg-surface px-4.5 text-[16px] font-semibold text-ink2 hover:bg-surface2 disabled:border-line disabled:bg-paper disabled:text-muted"
            >
              보류
            </button>
            <button
              type="button"
              onClick={() => setConfirming("approve")}
              disabled={busy || approveBlocked}
              className="tap rounded bg-human px-5.5 text-[16px] font-bold text-white hover:brightness-95 disabled:bg-line disabled:text-muted"
            >
              {hasChanges ? "변경 내용으로 승인" : "승인"}
            </button>
          </div>
        </div>

        {nothingLeft ? (
          <p className="text-right text-[14px] text-block">
            남는 문장이 없어요. 요약을 통째로 지우려면 요약 제거를 눌러 주세요.
          </p>
        ) : blankEdit ? (
          <p className="text-right text-[14px] text-block">
            고칠 문장이 비어 있어요. 문장을 빼려면 삭제를 눌러 주세요.
          </p>
        ) : null}

        {error ? (
          <p role="alert" className="text-right text-[14px] text-block">
            {error}
          </p>
        ) : null}

        <div className="flex justify-end">
          <button
            type="button"
            onClick={() => setConfirming("remove")}
            disabled={busy || item.processing}
            className="flex min-h-9 items-center gap-1.5 px-1 text-[14px] font-semibold text-block hover:underline disabled:text-muted disabled:no-underline"
          >
            <Trash2 aria-hidden size={14} strokeWidth={1.8} />
            요약 제거
          </button>
        </div>
      </div>

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
    </article>
  );
}

const SEGMENT = [
  { action: "KEEP", label: "유지", Icon: Check, on: "bg-ink2 text-white" },
  { action: "EDIT", label: "수정", Icon: Pencil, on: "bg-accent text-white" },
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
    ? "bg-accent text-white"
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

/* ── 요약할 내용 없음 카드 (#244) ──────────────────────── */

/**
 * AI 가 그날 기록에서 전할 내용을 찾지 못한 결과. 고칠 문장이 없어 [확인] · [요약 되돌리기] 만 둔다.
 * 교사가 "왜 없지?" 를 바로 볼 수 있게 묶인 기록 원본을 펼쳐 둔다.
 */
function NoContentItem({
  item,
  onResolved,
}: {
  item: SummaryItem;
  onResolved: (outcome: Outcome, message?: string) => void;
}) {
  const revalidator = useRevalidator();
  const [confirming, setConfirming] = useState<"confirm" | "revert" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const count = item.uncoveredEntries.length;

  async function decide(decision: "confirm" | "revert") {
    setBusy(true);
    setError(null);
    try {
      await decideGate1(item.id, { decision, version: item.version });
      onResolved(decision === "confirm" ? "confirmed" : "reverted");
      await revalidator.revalidate();
    } catch (err) {
      if (isStale(err)) {
        onResolved("stale", messageOf(err));
        await revalidator.revalidate();
      } else {
        setError(messageOf(err));
        if (needsReload(err)) await revalidator.revalidate();
      }
    } finally {
      setBusy(false);
      setConfirming(null);
    }
  }

  return (
    <article
      aria-label={`${item.childName} ${dateLabel(item.entryDate)} 요약할 내용 없음`}
      className="flex flex-col gap-4 rounded border border-line bg-surface px-6 pt-5.5 pb-5"
    >
      <ItemHeader
        item={item}
        badge={
          item.processing ? (
            <Pill tone="accent">요약 다시 만드는 중</Pill>
          ) : (
            <Pill tone="neutral">요약할 내용 없음</Pill>
          )
        }
      />

      {item.processing ? (
        <div
          role="status"
          className="flex items-center gap-2.5 rounded bg-accentsoft px-3.5 py-3 text-accentink"
        >
          <LoaderCircle aria-hidden size={18} strokeWidth={1.8} className="shrink-0" />
          <p className="text-[14px] leading-6">
            <b className="font-bold">새 기록을 반영하는 중이에요.</b> 결과가 곧 바뀌어서 지금은
            확인하거나 되돌릴 수 없어요.
          </p>
        </div>
      ) : null}

      <div className="flex flex-col gap-1 rounded bg-surface2/60 px-4 py-3.5">
        <p className="text-[16px] font-bold">이날 기록에서 요약할 내용을 찾지 못했어요</p>
        <p className="text-[14px] leading-6 text-ink2">
          AI 가 내용을 지어내지 않도록 요약을 비워 두었어요. 아래 기록을 읽고 정말 전할 내용이
          없으면 확인을, 요약이 필요하면 되돌리기를 눌러 주세요.
        </p>
      </div>

      {count > 0 ? (
        <section className="flex flex-col gap-2">
          <h2 className="text-[15px] font-bold text-ink2">이 날 기록 {count}건</h2>
          <ul className="flex flex-col gap-2">
            {item.uncoveredEntries.map((e) => (
              <li
                key={e.journalEntryId}
                className="rounded border border-line bg-surface px-3.5 py-3 text-[16px] leading-relaxed text-ink"
              >
                {e.content}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className="flex flex-col gap-1.5 border-t border-surface2 pt-3.5">
        <div className="flex flex-wrap justify-end gap-2">
          <button
            type="button"
            onClick={() => setConfirming("revert")}
            disabled={busy || item.processing}
            className="tap rounded border border-line2 bg-surface px-4.5 text-[16px] font-semibold text-ink2 hover:bg-surface2 disabled:border-line disabled:bg-paper disabled:text-muted"
          >
            요약 되돌리기
          </button>
          <button
            type="button"
            onClick={() => setConfirming("confirm")}
            disabled={busy || item.processing}
            className="tap rounded bg-human px-5.5 text-[16px] font-bold text-white hover:brightness-95 disabled:bg-line disabled:text-muted"
          >
            확인
          </button>
        </div>
        {error ? (
          <p role="alert" className="text-right text-[14px] text-block">
            {error}
          </p>
        ) : null}
      </div>

      {confirming === "confirm" ? (
        <ConfirmDialog
          title={`${item.childName} 기록을 요약 없이 끝낼까요?`}
          warning="확인하면 되돌릴 수 없어요."
          confirmLabel="확인하기"
          tone="human"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => decide("confirm")}
        >
          이날 기록{count > 0 ? ` ${count}건` : ""}을 "요약할 내용 없음"으로 끝내요. 아동 타임라인에는
          올라가지 않아요.
        </ConfirmDialog>
      ) : confirming === "revert" ? (
        <ConfirmDialog
          title={`${item.childName} 요약을 되돌릴까요?`}
          confirmLabel="되돌리기"
          tone="neutral"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => decide("revert")}
        >
          이 결과는 목록에서 빠지고 기록은 요약 전으로 돌아가요. 다시 요약을 요청하면 그 사이 들어온
          같은 날 기록과 함께 새로 요약해요.
        </ConfirmDialog>
      ) : null}
    </article>
  );
}

/* ── 보류함 카드 ─────────────────────────────────────── */

function HeldItem({
  item,
  onResolved,
}: {
  item: SummaryItem;
  onResolved: (outcome: Outcome, message?: string) => void;
}) {
  const revalidator = useRevalidator();
  const [confirming, setConfirming] = useState<"reopen" | "remove" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(kind: "reopen" | "remove") {
    setBusy(true);
    setError(null);
    try {
      if (kind === "reopen") await reopenGate1(item.id);
      else await decideGate1(item.id, { decision: "remove", version: item.version });
      onResolved(kind === "reopen" ? "reopened" : "removed");
      await revalidator.revalidate();
    } catch (err) {
      if (isStale(err)) {
        onResolved("stale", messageOf(err));
        await revalidator.revalidate();
      } else {
        setError(messageOf(err));
        if (needsReload(err)) await revalidator.revalidate();
      }
    } finally {
      setBusy(false);
      setConfirming(null);
    }
  }

  return (
    <article
      aria-label={`${item.childName} ${dateLabel(item.entryDate)} 요약, 보류함`}
      className="flex flex-col gap-3.5 rounded border border-line bg-surface px-6 pt-5.5 pb-5"
    >
      <ItemHeader item={item} badge={<Pill tone="neutral">보류함</Pill>} />
      <ReadOnlySentences item={item} />
      <p className="text-[14px] leading-6 text-muted">
        보류한 요약은 보호자와 다른 기관에 가지 않아요. 보류 해제하면 AI 요약 그대로 검토 대기로
        돌아가요.
      </p>
      {error ? (
        <p role="alert" className="text-right text-[14px] text-block">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center justify-end gap-3">
        <button
          type="button"
          onClick={() => setConfirming("remove")}
          disabled={busy}
          className="tap px-1 text-[14px] font-semibold text-block hover:underline disabled:text-muted"
        >
          요약 제거
        </button>
        <button
          type="button"
          onClick={() => setConfirming("reopen")}
          disabled={busy}
          className="tap rounded border border-accent bg-surface px-5 text-[16px] font-bold text-accentink hover:bg-accentsoft/50 disabled:opacity-60"
        >
          보류 해제
        </button>
      </div>

      {confirming === "reopen" ? (
        <ConfirmDialog
          title={`${item.childName} 요약의 보류를 해제할까요?`}
          confirmLabel="보류 해제"
          tone="accent"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => run("reopen")}
        >
          AI 요약 그대로 검토 대기로 돌아가요.
        </ConfirmDialog>
      ) : confirming === "remove" ? (
        <ConfirmDialog
          title={`${item.childName} 요약을 제거할까요?`}
          warning="제거하면 되돌릴 수 없어요."
          confirmLabel="제거하기"
          tone="block"
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => run("remove")}
        >
          이 요약과 그 일지를 모두 지워요.
        </ConfirmDialog>
      ) : null}
    </article>
  );
}

/* ── 처리한 뒤 ─────────────────────────────────────── */

const OUTCOME: Record<Exclude<Outcome, "stale">, { title: string; tone: "pass" | "neutral" }> = {
  approved: { title: "AI 요약 그대로 승인했어요", tone: "pass" },
  corrected: { title: "고친 내용으로 승인했어요", tone: "pass" },
  held: { title: "보류함으로 옮겼어요", tone: "neutral" },
  removed: { title: "요약을 제거했어요", tone: "neutral" },
  reopened: { title: "보류를 해제했어요 · 검토 대기에서 다시 볼 수 있어요", tone: "pass" },
  confirmed: { title: "요약할 내용 없음으로 확인했어요", tone: "pass" },
  reverted: { title: "요약 전으로 되돌렸어요", tone: "neutral" },
};

function ResolvedCard({ item, outcome, message }: { item: SummaryItem; outcome: Outcome; message?: string }) {
  const sub = `${item.childName} · ${dateLabel(item.entryDate)} 요약`;
  if (outcome === "stale") {
    return (
      <Card className="border-line2">
        <p className="mb-1 text-[16px] font-bold text-ink2">{sub}</p>
        <p role="alert" className="text-[15px] leading-7 text-muted">
          {message}
        </p>
      </Card>
    );
  }
  const o = OUTCOME[outcome];
  return (
    <section className="flex flex-wrap items-center gap-3 rounded border border-line bg-surface px-6 py-5">
      <span
        aria-hidden
        className={`flex size-9 items-center justify-center rounded-full ${
          o.tone === "pass" ? "bg-passsoft text-pass" : "bg-surface2 text-ink2"
        }`}
      >
        <Check size={18} strokeWidth={2} />
      </span>
      <div className="flex flex-col gap-0.5">
        <p role="status" className="text-[16px] font-bold">
          {o.title}
        </p>
        <p className="text-[14px] text-muted">{sub}</p>
      </div>
    </section>
  );
}

/* ── 화면 ─────────────────────────────────────────── */

/** 백엔드에 아직 없으면 mock 데이터 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function Gate1Route() {
  if (!isReady("gate1")) return <NotReadyPage title={TITLE} description={DESCRIPTION} />;
  return <Gate1Page />;
}

function Gate1Page() {
  const { pending, held } = useLoaderData<typeof clientLoader>();
  const [tab, setTab] = useState<Tab>("pending");
  /**
   * 처리하면 revalidate 로 그 요약이 목록에서 빠진다 — 결과 카드가 바로 사라지지 않도록
   * 이번 방문 동안만 탭별로 기억한다. 키는 "탭:id" (보류 → 해제처럼 탭을 오가는 요약이 있다).
   */
  const [resolved, setResolved] = useState<
    Record<string, { item: SummaryItem; outcome: Outcome; message?: string }>
  >({});

  function resolve(from: Tab, item: SummaryItem, outcome: Outcome, message?: string) {
    const other: Tab = from === "pending" ? "hold" : "pending";
    setResolved((r) => {
      const next = { ...r, [`${from}:${item.id}`]: { item, outcome, message } };
      delete next[`${other}:${item.id}`];
      return next;
    });
  }

  const list = tab === "pending" ? pending : held;
  const live = list.filter((s) => !resolved[`${tab}:${s.id}`]);
  const done = Object.entries(resolved)
    .filter(([key]) => key.startsWith(`${tab}:`))
    .map(([, r]) => r);

  const tabs: { key: Tab; label: string; count: number }[] = [
    { key: "pending", label: "검토 대기", count: pending.length },
    { key: "hold", label: "보류함", count: held.length },
  ];

  return (
    <>
      <PageHeader
        title={TITLE}
        description={DESCRIPTION}
        right={
          <div role="tablist" aria-label="검토 상태" className="flex gap-1 rounded-md bg-surface2 p-1">
            {tabs.map((t) => (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={tab === t.key}
                onClick={() => setTab(t.key)}
                className={`min-h-10 rounded px-4 text-[15px] font-semibold ${
                  tab === t.key
                    ? "bg-surface text-ink shadow-[0_1px_2px_rgba(22,28,27,0.12)]"
                    : "text-ink2 hover:text-ink"
                }`}
              >
                {t.label} <span className="ml-1 font-bold tabular-nums">{t.count}</span>
              </button>
            ))}
          </div>
        }
      />

      <div className="flex flex-col gap-5">
        {live.length === 0 && done.length === 0 ? (
          tab === "pending" ? (
            <EmptyState
              icon="✓"
              title="검토할 요약이 없어요"
              description="기록을 등록하고 요약을 요청하면 매칭 · 검증 · 요약을 거쳐 여기로 와요."
            />
          ) : (
            <EmptyState icon="◦" title="보류한 요약이 없어요" />
          )
        ) : null}

        {live.map((s) =>
          tab === "pending" && s.noContent ? (
            <NoContentItem
              key={s.id}
              item={s}
              onResolved={(outcome, message) => resolve("pending", s, outcome, message)}
            />
          ) : tab === "pending" ? (
            <PendingItem
              key={s.id}
              item={s}
              onResolved={(outcome, message) => resolve("pending", s, outcome, message)}
            />
          ) : (
            <HeldItem
              key={s.id}
              item={s}
              onResolved={(outcome, message) => resolve("hold", s, outcome, message)}
            />
          ),
        )}

        {done.map((r) => (
          <ResolvedCard key={r.item.id} item={r.item} outcome={r.outcome} message={r.message} />
        ))}
      </div>
    </>
  );
}
