import { ChevronDown, Plus, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import { WorkHead } from "@/components/org/WorkList";
import { Busy, Card, ConfirmDialog, EmptyState, PanelHead, ReloginButton } from "@/components/ui";
import { Pager, usePage } from "@/components/ui/Pager";
import {
  ApiError,
  cancelChecklist,
  getChildren,
  getSentChecklists,
  sendChecklist,
} from "@/lib/api";
import { isLoginLost, type ShownError } from "@/lib/apiError";
import { formatDateTime } from "@/lib/datetime";
import type { ChildListItem, SentChecklist, SentChecklistPage } from "@/lib/types";

const MAX_LINES = 10;
const MAX_CHARS = 100;

export async function clientLoader() {
  const [page, roster] = await Promise.all([getSentChecklists(), getChildren()]);
  return { page, roster };
}

/**
 * I-15 준비물·확인사항 — 기관이 명부의 아이들에게 준비물 · 확인사항을 보내고, 누가 확인했는지 본다 (api-spec O-70 ~ O-72).
 *
 * 받는 아이는 보낸 순간의 명부로 정해지고, 공유 동의와는 상관없다. 보낸 뒤에는 고칠 수 없어 보내기 전에 한 번 더 묻는다.
 * 보호자 중 한 명이 확인하면 그 아이 몫은 끝난다.
 */
export default function ChecklistsPage() {
  const { page, roster } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  /*
   * 이어 본 쪽은 **그 쪽을 이어 붙인 첫 쪽**에 묶는다. 보내기 · 내리기로 loader 가 첫 쪽을 새로 받으면 그 사이 순서가 밀려,
   * 옛 이어 보기를 붙이면 요청이 빠지거나 겹친다. 그래서 첫 쪽이 바뀌면 이어 본 쪽은 버리고 새 nextBefore 부터 다시 본다.
   * 갱신 전에 보낸 더 보기 요청의 응답 · 오류도 같은 이유로 버린다(for 가 지금 첫 쪽이 아니면 쓰지 않는다).
   */
  const [more, setMore] = useState<{ for: SentChecklistPage; items: SentChecklist[]; next: string | null } | null>(
    null,
  );
  const [loadingFor, setLoadingFor] = useState<SentChecklistPage | null>(null);
  const [cancelling, setCancelling] = useState<SentChecklist | null>(null);
  const [cancelPending, setCancelPending] = useState(false);
  // 더 보기와 내리기가 함께 쓴다. 문구와 [다시 로그인]을 한 값으로 들어, 내리기 401 뒤 더 보기가 실패하면 버튼도 사라진다
  const [listError, setListError] = useState<ShownError | null>(null);
  const latestPage = useRef(page);
  latestPage.current = page;

  const extra = more?.for === page ? more : null;
  const cursor = extra ? extra.next : page.nextBefore;
  const sent = [...page.checklists, ...(extra?.items ?? [])];
  const loadingMore = loadingFor === page;

  async function loadMore() {
    if (!cursor || loadingMore) return;
    const base = page;
    setLoadingFor(base);
    setListError(null);
    try {
      const next = await getSentChecklists(cursor);
      // 새로 받은 요청이 시작되는 쪽으로 넘긴다 — 지금 쪽에 머물면 눌러도 아무 일 없는 것처럼 보인다
      if (next.checklists.length) jumpTo.current = sent.length;
      setMore((prev) => ({
        for: base,
        items: [...(prev?.for === base ? prev.items : []), ...next.checklists],
        next: next.nextBefore,
      }));
    } catch {
      if (latestPage.current === base) setListError({ text: "이전 요청을 불러오지 못했어요. 다시 시도해 주세요.", loginLost: false });
    } finally {
      setLoadingFor((current) => (current === base ? null : current));
    }
  }

  async function confirmCancel() {
    if (!cancelling) return;
    setCancelPending(true);
    setListError(null);
    try {
      await cancelChecklist(cancelling.id);
      setCancelling(null);
      await revalidator.revalidate();
    } catch (err) {
      setListError(
        isLoginLost(err)
          ? { text: "로그인이 풀렸어요. 다시 로그인한 뒤 시도해 주세요.", loginLost: true }
          : { text: "내리지 못했어요. 새로고침한 뒤 다시 시도해 주세요.", loginLost: false },
      );
      setCancelling(null);
    } finally {
      setCancelPending(false);
    }
  }

  // 받아 둔 목록을 10건씩 쪽으로 나눈다. 더 오래된 요청은 아래 [이전 요청 더 보기]로 서버에서 더 받는다
  const sentPage = usePage(sent);
  /** 더 보기로 받은 첫 요청의 순번 — 목록이 늘어난 뒤 그 쪽으로 넘긴다 */
  const jumpTo = useRef<number | null>(null);
  useEffect(() => {
    if (jumpTo.current == null || sent.length <= jumpTo.current) return;
    sentPage.setPage(Math.floor(jumpTo.current / sentPage.size) + 1);
    jumpTo.current = null;
  }, [sent.length]);

  return (
    <>
      <WorkHead title="준비물·확인사항" tour="lists" />

      <div className="flex flex-col gap-5">
        <div data-tour="composer">
          <Composer roster={roster} onSent={() => revalidator.revalidate()} />
        </div>

        <div data-tour="sent">
          <Card>
          <PanelHead title={`보낸 요청 ${sent.length}${cursor ? "+" : ""}건`} />
          {listError ? (
            <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-2 rounded bg-blocksoft px-3 py-2">
              <p role="alert" className="text-[14px] text-block">
                {listError.text}
              </p>
              {listError.loginLost ? <ReloginButton role="org" /> : null}
            </div>
          ) : null}
          {sent.length === 0 ? (
            <EmptyState icon="none" title="아직 보낸 요청이 없어요" description="위에서 첫 요청을 보내 보세요." />
          ) : (
            <>
              <ul className="flex flex-col gap-3">
                {sentPage.items.map((c) => (
                  <SentRow key={c.id} checklist={c} onCancel={() => setCancelling(c)} />
                ))}
              </ul>
              <Pager {...sentPage} onChange={sentPage.setPage} />
            </>
          )}
          {/* 더 오래된 요청은 마지막 쪽에서만 더 받는다 */}
          {cursor && sentPage.page === sentPage.pages ? (
            <button
              type="button"
              onClick={() => void loadMore()}
              disabled={loadingMore}
              className="tap mt-4 w-full rounded border border-line2 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
            >
              {loadingMore ? <Busy>불러오는 중…</Busy> : "이전 요청 더 보기"}
            </button>
          ) : null}
          </Card>
        </div>
      </div>

      {cancelling ? (
        <ConfirmDialog
          title="이 요청을 내릴까요?"
          warning="받은 아이 모두의 보호자 화면에서 사라져요. 되돌릴 수 없어요."
          confirmLabel="내리기"
          tone="block"
          busy={cancelPending}
          onCancel={() => setCancelling(null)}
          onConfirm={() => void confirmCancel()}
        >
          {cancelling.items[0]}
          {cancelling.items.length > 1 ? ` 외 ${cancelling.items.length - 1}줄` : ""}
        </ConfirmDialog>
      ) : null}
    </>
  );
}

/** 새 요청 — 항목 줄 · 받을 아이 · 보내기 전 확인 */
function Composer({ roster, onSent }: { roster: ChildListItem[]; onSent: () => void }) {
  const [open, setOpen] = useState(false);
  const [lines, setLines] = useState<string[]>([""]);
  const [selected, setSelected] = useState<string[]>([]);
  const [asking, setAsking] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ShownError | null>(null);

  const items = lines.map((l) => l.trim()).filter((l) => l.length > 0);
  const tooLong = lines.some((l) => l.trim().length > MAX_CHARS);
  const allSelected = roster.length > 0 && selected.length === roster.length;
  const canSend = items.length > 0 && !tooLong && selected.length > 0;

  function reset() {
    setLines([""]);
    setSelected([]);
    setError(null);
  }

  function toggle(id: string) {
    setSelected((prev) => (prev.includes(id) ? prev.filter((s) => s !== id) : [...prev, id]));
  }

  async function send() {
    setPending(true);
    setError(null);
    try {
      await sendChecklist({ items, childIds: selected });
      setAsking(false);
      reset();
      setOpen(false);
      onSent();
    } catch (err) {
      setAsking(false);
      if (err instanceof ApiError && err.code === "INVALID_CHECKLIST_TARGET") {
        setError({ text: "명부에 없는 아이가 있어요. 목록을 새로 불러왔으니 다시 골라 주세요.", loginLost: false });
        setSelected([]);
        onSent();
      } else if (err instanceof ApiError && err.code === "INVALID_REQUEST") {
        setError({ text: `항목은 1~${MAX_LINES}줄, 줄마다 ${MAX_CHARS}자까지 쓸 수 있어요.`, loginLost: false });
      } else {
        setError(
          isLoginLost(err)
            ? { text: "로그인이 풀렸어요. 다시 로그인한 뒤 시도해 주세요.", loginLost: true }
            : { text: "보내지 못했어요. 다시 시도해 주세요.", loginLost: false },
        );
      }
    } finally {
      setPending(false);
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="tap inline-flex items-center gap-1.5 self-start rounded-full bg-ink px-5 text-[15px] font-bold text-paper hover:brightness-110"
      >
        <Plus aria-hidden size={16} strokeWidth={2.4} />새 요청
      </button>
    );
  }

  return (
    <Card>
      <PanelHead
        title="새 요청"
        note="보낸 뒤에는 고칠 수 없어요. 잘못 보냈으면 내리고 다시 보내 주세요."
        right={
          <button
            type="button"
            onClick={() => {
              reset();
              setOpen(false);
            }}
            aria-label="새 요청 닫기"
            className="tap rounded-full px-2 text-muted hover:bg-surface2"
          >
            <X aria-hidden size={18} />
          </button>
        }
      />

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (canSend) setAsking(true);
        }}
        className="flex flex-col gap-5"
      >
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 text-[15px] font-bold">
            항목 <span className="font-normal text-muted">· {items.length} / {MAX_LINES}줄</span>
          </legend>
          {lines.map((line, i) => (
            <div key={i} className="flex items-center gap-2">
              <input
                id={`checklist-line-${i}`}
                value={line}
                onChange={(e) => setLines((prev) => prev.map((l, j) => (j === i ? e.target.value : l)))}
                maxLength={MAX_CHARS + 20}
                placeholder={i === 0 ? "예: 실내화를 새로 준비해주세요." : "항목 추가"}
                aria-label={`항목 ${i + 1}`}
                aria-invalid={line.trim().length > MAX_CHARS ? true : undefined}
                className="tap min-w-0 flex-1 rounded border border-line2 bg-surface px-3 text-[15px] outline-none focus:border-accent aria-invalid:border-block"
              />
              <span
                className={`w-14 shrink-0 text-right text-[13px] tabular-nums ${
                  line.trim().length > MAX_CHARS ? "text-block" : "text-muted"
                }`}
              >
                {line.trim().length}/{MAX_CHARS}
              </span>
              {lines.length > 1 ? (
                <button
                  type="button"
                  onClick={() => setLines((prev) => prev.filter((_, j) => j !== i))}
                  aria-label={`항목 ${i + 1} 지우기`}
                  className="tap shrink-0 rounded px-2 text-muted hover:bg-surface2"
                >
                  <Trash2 aria-hidden size={16} />
                </button>
              ) : null}
            </div>
          ))}
          {lines.length < MAX_LINES ? (
            <button
              type="button"
              onClick={() => setLines((prev) => [...prev, ""])}
              className="tap self-start rounded px-2 text-[14px] font-semibold text-accentink hover:bg-surface2"
            >
              + 줄 추가
            </button>
          ) : null}
        </fieldset>

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 flex w-full flex-wrap items-center justify-between gap-2 text-[15px] font-bold">
            <span>
              받을 아이 <span className="font-normal text-muted">· {selected.length}명 고름</span>
            </span>
          </legend>
          {roster.length === 0 ? (
            <p className="text-[14px] text-muted">명부에 아이가 없어요. 아동 목록에서 먼저 등록해 주세요.</p>
          ) : (
            <>
              <label className="flex items-center gap-2.5 text-[15px] font-semibold">
                <input
                  id="checklist-all"
                  type="checkbox"
                  checked={allSelected}
                  onChange={() => setSelected(allSelected ? [] : roster.map((c) => c.id))}
                  className="size-4 accent-accent"
                />
                명부 전체 ({roster.length}명)
              </label>
              <ul className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-2">
                {roster.map((c) => (
                  <li key={c.id}>
                    <label className="flex cursor-pointer items-center gap-2 rounded border border-line px-3 py-2 text-[15px] has-checked:border-accent has-checked:bg-accentsoft">
                      <input
                        id={`checklist-child-${c.id}`}
                        type="checkbox"
                        checked={selected.includes(c.id)}
                        onChange={() => toggle(c.id)}
                        className="size-4 accent-accent"
                      />
                      <span className="min-w-0 truncate">{c.name}</span>
                    </label>
                  </li>
                ))}
              </ul>
            </>
          )}
        </fieldset>

        {error ? (
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded bg-blocksoft px-3 py-2">
            <p role="alert" className="text-[14px] text-block">
              {error.text}
            </p>
            {/* 이 줄은 보내기 폼 안이다 — 버튼은 type="button" 이라 폼을 제출하지 않는다 */}
            {error.loginLost ? <ReloginButton role="org" /> : null}
          </div>
        ) : null}

        <button
          disabled={!canSend || pending}
          className="tap self-end rounded-full bg-ink px-6 text-[15px] font-bold text-paper hover:brightness-110 disabled:bg-surface2 disabled:text-muted"
        >
          보내기
        </button>
      </form>

      {asking ? (
        <ConfirmDialog
          title={`${selected.length}명에게 보냅니다`}
          warning="보낸 뒤에는 고칠 수 없고 내리기만 할 수 있어요."
          confirmLabel="보내기"
          busy={pending}
          onCancel={() => setAsking(false)}
          onConfirm={() => void send()}
        >
          <ul className="list-disc pl-5">
            {items.map((item, i) => (
              <li key={i}>{item}</li>
            ))}
          </ul>
        </ConfirmDialog>
      ) : null}
    </Card>
  );
}

/** 보낸 요청 한 줄 — 펼치면 아이별 확인 */
function SentRow({ checklist, onCancel }: { checklist: SentChecklist; onCancel: () => void }) {
  const [open, setOpen] = useState(false);
  const first = checklist.items[0];
  const rest = checklist.items.length - 1;

  return (
    <li className="rounded border border-line">
      {/* 줄 아무 곳이나 눌러도 펼친다(키보드는 안쪽 버튼으로). 안쪽 버튼은 stopPropagation 으로 두 번 안 바뀌게 */}
      <div
        onClick={() => setOpen((o) => !o)}
        className="flex cursor-pointer flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3"
      >
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            setOpen((o) => !o);
          }}
          aria-expanded={open}
          className="group flex min-w-0 flex-1 items-center gap-3 text-left"
        >
          <ChevronDown
            aria-hidden
            size={18}
            className={`shrink-0 text-muted transition-transform duration-200 ${open ? "rotate-180" : ""}`}
          />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[16px] font-bold group-hover:text-accentink">
              {first}
              {rest > 0 ? <span className="font-normal text-muted"> 외 {rest}줄</span> : null}
            </span>
            <span className="block text-[13px] text-muted tabular-nums">{formatDateTime(checklist.sentAt)} 보냄</span>
          </span>
        </button>
        <span
          className={`shrink-0 rounded-full px-3 py-1 text-[14px] font-bold tabular-nums ${
            checklist.total > 0 && checklist.confirmed === checklist.total
              ? "bg-passsoft text-pass"
              : "bg-surface2 text-ink2"
          }`}
        >
          확인 {checklist.confirmed} / {checklist.total}
        </span>
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onCancel();
          }}
          className="tap shrink-0 rounded px-3 text-[14px] font-semibold text-block hover:bg-blocksoft"
        >
          내리기
        </button>
      </div>

      {open ? (
        <div className="border-t border-line px-4 py-3">
          <ul className="mb-3 flex flex-col gap-1 text-[15px] leading-6">
            {checklist.items.map((item, i) => (
              <li key={i} className="flex gap-1.5">
                <span aria-hidden className="text-muted">
                  ◦
                </span>
                {item}
              </li>
            ))}
          </ul>
          <ul className="flex flex-col divide-y divide-line">
            {checklist.targets.map((t) => (
              <li key={t.child.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-[15px]">
                <span className={t.onRoster ? "" : "text-muted line-through"}>{t.child.name ?? "이름 없음"}</span>
                <span className="text-[14px] tabular-nums">
                  {!t.onRoster ? (
                    <span className="text-muted">명부에서 빠짐</span>
                  ) : t.confirmedAt ? (
                    <span className="text-pass">
                      확인 · {t.confirmedByName ?? "보호자"} · {formatDateTime(t.confirmedAt)}
                    </span>
                  ) : (
                    <span className="text-muted">확인 전</span>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </li>
  );
}
