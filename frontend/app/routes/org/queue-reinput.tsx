import { useCallback, useEffect, useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import { Busy, Card, EmptyState, PageHeader, ReloginButton, ValidationBadge } from "@/components/ui";
import {
  ApiError,
  getBlockedQueue,
  MAX_REVISED_CONTENT,
  resolveBlockedItem,
  reviseBlockedItem,
} from "@/lib/api";
import { isLoginLost, shownError, type ShownError } from "@/lib/apiError";
import {
  forgetReinputDrafts,
  readReinputDrafts,
  saveReinputDraft,
  type ReinputDraft,
} from "@/lib/reinputDraft";
import type { BlockedItem } from "@/lib/types";

type Action = "revise" | "hold";

/** 페이지 위 안내. 처리가 끝났거나 카드가 바뀌어 카드 안에 남길 수 없는 말을 여기 둔다 */
type Notice = { tone: "done" | "warn"; text: string };

/** 처리 실패 안내. 목록을 새로 받으면 카드가 사라지거나 바뀌므로 그때는 페이지 위에 남긴다 */
type Failure = { text: string; refresh: boolean };

export async function clientLoader() {
  const items = await getBlockedQueue();
  /*
   * 로그인이 풀려 맡겨 둔 고치던 본문. 목록을 받은 뒤에 읽는다 — 로그인이 풀린 채 오면 위에서 401 로 끝난다.
   * 지우지 않는다 — 탐색이 취소돼 이 화면이 안 열려도 loader 는 끝까지 돈다. 카드가 그린 뒤 지운다.
   * 같은 기록인지(원래 본문)는 카드가 그때의 본문과 맞춰 본다
   */
  const saved = readReinputDrafts();
  const ids = new Set(items.map((i) => i.id));
  const carried = {
    drafts: Object.fromEntries(Object.entries(saved).filter(([id]) => ids.has(id))),
    // 이미 처리됐거나, 고쳐서 다시 검사해 새 검사 결과로 바뀌었거나, 다른 기관 기록이다
    gone: Object.keys(saved).filter((id) => !ids.has(id)),
  };
  return { items, carried };
}

const RESTORED = "로그인 전에 고치던 내용을 되살렸어요.";
const DROPPED = "로그인하는 사이 기록이 바뀌어 고치던 내용은 지웠어요.";

/** 맡겨 둔 본문을 이 카드에 되살려도 되는지. 원래 본문이 그사이 바뀌었으면 옛 본문 위에 덮어쓰지 않는다 */
function restoredOf(item: BlockedItem, draft: ReinputDraft | undefined): string | null {
  if (!draft || item.record.fullContent == null) return null;
  return draft.original === item.record.fullContent ? draft.draft : null;
}

export default function ReinputQueuePage() {
  const { items, carried: loaded } = useLoaderData<typeof clientLoader>();
  // 고친 카드는 큐에서 빠져 사라진다. 무엇이 됐는지 페이지에 남겨 둔다
  const [notice, setNotice] = useState<Notice | null>(null);

  /*
   * 로그인이 풀려 맡겨 둔 본문. 처음 받은 것만 붙잡는다 — 처리 뒤 다시 불러오기로 loader 가 다시 돌아도 덮지 않는다.
   * 탭이 없어 카드가 한 번에 그려지므로 맡긴 칸은 첫 화면에서 모두 쓰인다
   */
  const [carried, setCarried] = useState(loaded);
  const [loggedOut, setLoggedOut] = useState(false);

  // 목록에서 빠진 기록은 위 한 줄로 알리고 지운다. 번호를 골라 지우므로 두 번 돌아도 같다
  useEffect(() => {
    forgetReinputDrafts(carried.gone);
  }, [carried.gone]);

  const onCarriedUsed = useCallback((id: string) => {
    setCarried((c) => {
      if (!(id in c.drafts)) return c;
      const drafts = { ...c.drafts };
      delete drafts[id];
      return { ...c, drafts };
    });
    forgetReinputDrafts([id]);
  }, []);
  const onLoggedOut = useCallback(() => setLoggedOut(true), []);

  // 401 뒤에 다른 처리가 서버에 닿았다 — 다른 탭에서 다시 로그인했다. 이 화면에서 맡긴 본문은 이제 필요 없다
  // 맡긴 칸은 첫 화면에서 모두 쓰였으므로 저장소에 남은 것은 이 화면이 401 뒤에 쓴 것뿐이다
  function onReached() {
    if (!loggedOut) return;
    setLoggedOut(false);
    forgetReinputDrafts(Object.keys(readReinputDrafts()));
  }

  return (
    <>
      <PageHeader
        title="검증 수정 요청"
      />

      {carried.gone.length > 0 ? (
        <p role="status" className="mb-4 rounded bg-reviewsoft px-3 py-2 text-[15px] text-review">
          로그인하는 사이 {carried.gone.length}건은 이미 처리돼 고치던 내용을 지웠어요.
        </p>
      ) : null}

      {notice ? (
        <p
          role="status"
          className={`mb-4 rounded px-3 py-2 text-[15px] ${
            notice.tone === "done" ? "bg-passsoft text-pass" : "bg-reviewsoft text-review"
          }`}
        >
          {notice.text}
        </p>
      ) : null}

      {items.length === 0 ? (
        <EmptyState icon="done" title="멈춘 기록이 없습니다" />
      ) : (
        <div className="flex flex-col gap-4">
          {items.map((item) => (
            <BlockedCard
              key={item.id}
              item={item}
              carriedDraft={carried.drafts[item.id]}
              loggedOut={loggedOut}
              onCarriedUsed={onCarriedUsed}
              onLoggedOut={onLoggedOut}
              onReached={onReached}
              onNotice={setNotice}
            />
          ))}
        </div>
      )}
    </>
  );
}

function BlockedCard({
  item,
  carriedDraft,
  loggedOut,
  onCarriedUsed,
  onLoggedOut,
  onReached,
  onNotice,
}: {
  item: BlockedItem;
  /** 로그인이 풀려 맡겨 둔 이 기록의 고치던 본문. 쓰고 나면 페이지가 빼서 다시 오지 않는다 */
  carriedDraft: ReinputDraft | undefined;
  /** 이 화면에서 어느 카드든 401 을 받았다 — 이 카드도 고칠 때마다 맡겨 둔다 */
  loggedOut: boolean;
  onCarriedUsed: (itemId: string) => void;
  onLoggedOut: () => void;
  /** 요청이 서버에 닿았다(성공이거나 401 이 아닌 실패) — 로그인이 살아 있다 */
  onReached: () => void;
  /** 페이지 위 안내. 다른 처리를 시작하면 지난 안내를 지운다 */
  onNotice: (notice: Notice | null) => void;
}) {
  const revalidator = useRevalidator();
  const [busy, setBusy] = useState<Action | null>(null);
  const [error, setError] = useState<ShownError | null>(null);
  const [restored] = useState(() => restoredOf(item, carriedDraft));
  const [editing, setEditing] = useState(restored !== null);
  const [cardNotice, setCardNotice] = useState<string | null>(() =>
    carriedDraft ? (restored !== null ? RESTORED : DROPPED) : null,
  );

  /*
   * 막힌 문장이 60자 뒤에 있으면 preview 로는 무엇을 고쳐야 할지 안 보인다.
   * 전체 본문을 펼쳐 두고, 길면 카드 안에서 스크롤한다 (확인 필요 큐와 같은 방식, #112).
   */
  const content = item.record.fullContent ?? item.record.preview;
  const [draft, setDraft] = useState(() => restored ?? item.record.fullContent ?? "");
  const meta = [item.childName, item.record.type, formatDate(item.record.capturedAt)].filter(
    Boolean,
  );

  // 서버도 같은 이유로 거절한다 — 안 고친 채 다시 돌려 판정이 바뀌길 기다리지 않게 (#357)
  const unchanged = draft.trim() === (item.record.fullContent ?? "").trim();
  const canSave = busy === null && draft.trim() !== "" && !unchanged;

  // 맡겨 둔 본문을 화면에 반영했다 — 되살렸든 버렸든 지운다
  useEffect(() => {
    if (carriedDraft) onCarriedUsed(item.id);
  }, [carriedDraft, item.id, onCarriedUsed]);

  // 로그인이 풀린 뒤로는 고칠 때마다 맡긴 본문을 갱신한다. 고치기를 닫았거나 원래대로면 칸을 지운다
  const original = item.record.fullContent;
  const touched = editing && original != null && !unchanged;
  useEffect(() => {
    if (!loggedOut) return;
    saveReinputDraft(item.id, touched && original != null ? { original, draft } : null);
  }, [loggedOut, touched, original, draft, item.id]);

  async function run(action: Action) {
    setBusy(action);
    setError(null);
    setCardNotice(null);
    onNotice(null);
    try {
      if (action === "revise") {
        await reviseBlockedItem(item.id, draft);
        onNotice({
          tone: "done",
          text: "다시 검사를 시작했어요. 통과하면 오늘의 업무에서 요약할 수 있어요.",
        });
      } else {
        await resolveBlockedItem(item.id, "hold");
      }
      onReached();
      await revalidator.revalidate();
    } catch (err) {
      const failure = failureOf(err, action);
      // 통신 오류에서는 로그인 여부를 알 수 없다 — 맡긴 본문과 이후 편집의 보관을 유지한다
      if (isLoginLost(err)) onLoggedOut();
      else if (err instanceof ApiError) onReached();
      if (failure.refresh) {
        // 이미 처리됐거나 카드가 바뀐 건이면 목록을 새로 받아 화면을 서버와 맞춘다. 이 카드는 사라지거나
        // 새 카드로 바뀌므로 안내는 페이지 위에 남긴다
        onNotice({ tone: "warn", text: failure.text });
        await revalidator.revalidate();
      } else {
        setError(shownError(err, failure.text));
      }
    } finally {
      setBusy(null);
    }
  }

  function cancelEdit() {
    setDraft(item.record.fullContent ?? "");
    setEditing(false);
    setError(null);
  }

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <ValidationBadge status="BLOCK" />
        <span className="text-[15px] font-semibold break-all">{item.record.fileName}</span>
        {meta.length > 0 ? (
          <span className="text-[14px] text-muted">{meta.join(" · ")}</span>
        ) : null}
      </div>

      <p className="mb-3 rounded border border-block/30 bg-blocksoft px-3 py-2 text-[15px] text-block">
        <b className="font-semibold">멈춘 이유</b> · {item.violationReason}
      </p>

      {cardNotice ? (
        <p role="status" className="mb-3 rounded bg-reviewsoft px-3 py-2 text-[14px] text-review">
          {cardNotice}
        </p>
      ) : null}

      {editing ? (
        <div className="mb-4 flex flex-col gap-1.5">
          <textarea
            aria-label="고칠 기록 본문"
            rows={8}
            maxLength={MAX_REVISED_CONTENT}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="w-full resize-y rounded border border-line2 bg-surface px-3 py-2.5 text-[16px] leading-7 outline-none focus:border-ink focus:ring-2 focus:ring-ink/10"
          />
          <p className="text-[13px] text-muted">
            멈춘 이유에 적힌 부분을 고친 뒤 저장하세요. 저장하면 이 본문으로 다시 검사해요. 올린 원본
            파일은 바뀌지 않아요.
          </p>
        </div>
      ) : content ? (
        <blockquote
          tabIndex={0}
          aria-label="기록 본문"
          className="mb-4 max-h-72 overflow-y-auto rounded bg-surface2 px-3 py-2.5 text-[15px] leading-7 whitespace-pre-wrap text-ink2"
        >
          {content}
        </blockquote>
      ) : null}
      {!editing && item.record.fullContent == null ? (
        <p role="status" className="mb-4 rounded bg-reviewsoft px-3 py-2 text-[14px] text-review">
          {item.record.preview
            ? "전체 본문을 불러오지 못해 앞부분 미리보기만 표시합니다. 본문을 고칠 수 없어요."
            : "본문을 불러오지 못했습니다. 파일 이름과 날짜로 원본을 찾아 확인해 주세요."}
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {editing ? (
          <>
            <button
              onClick={() => run("revise")}
              disabled={!canSave}
              className="tap rounded bg-ink px-4 text-[15px] font-semibold text-paper hover:brightness-110 disabled:opacity-60"
            >
              {busy === "revise" ? <Busy>저장 중…</Busy> : "저장하고 다시 검사"}
            </button>
            <button
              onClick={cancelEdit}
              disabled={busy !== null}
              className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
            >
              취소
            </button>
          </>
        ) : (
          <>
            <button
              onClick={() => setEditing(true)}
              disabled={busy !== null || item.record.fullContent == null}
              className="tap rounded bg-ink px-4 text-[15px] font-semibold text-paper hover:brightness-110 disabled:opacity-60"
            >
              고쳐서 다시 검사
            </button>
            <button
              onClick={() => run("hold")}
              disabled={busy !== null}
              className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
            >
              {busy === "hold" ? <Busy>처리 중…</Busy> : "이 기록 보류"}
            </button>
          </>
        )}
      </div>

      {error ? (
        <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1">
          <p role="alert" className="text-[14px] text-block">
            {error.text}
          </p>
          {error.loginLost ? <ReloginButton role="org" /> : null}
        </div>
      ) : null}
    </Card>
  );
}

/** BE 는 `YYYY-MM-DD` 를 준다. 읽을 수 없는 값이면 칸째 뺀다 */
function formatDate(value: string | null): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString("ko-KR", { month: "long", day: "numeric" });
}

function failureOf(err: unknown, action: Action): Failure {
  if (!(err instanceof ApiError)) {
    return { text: "네트워크 연결을 확인하고 다시 시도해 주세요.", refresh: false };
  }
  if (err.status === 400) {
    switch (err.code) {
      case "VALIDATION_RESULT_OUTDATED":
        return {
          text:
            "이미 고쳐 다시 검사했는데 또 멈춘 기록이에요. 새로 멈춘 이유를 보고 다시 처리해 주세요." +
            (action === "revise" ? " 고치던 글은 저장되지 않았어요." : ""),
          refresh: true,
        };
      case "INVALID_REQUEST":
        return { text: "본문이 비어 있거나 너무 길어요. 고친 내용을 확인해 주세요.", refresh: false };
      case "VALIDATION_RESULT_CONTENT_UNCHANGED":
        return { text: "고친 내용이 없어요. 멈춘 이유에 적힌 부분을 고쳐 주세요.", refresh: false };
      case "VALIDATION_RESULT_CHILD_NOT_SHARED":
        return {
          text: "아이가 명단에서 빠졌거나 공유 동의가 꺼져 다시 검사할 수 없어요. 이 기록은 보류해 주세요.",
          refresh: false,
        };
    }
  }
  if (err.status === 400 || err.status === 404) {
    return {
      text:
        "이미 처리됐거나 없어진 기록이에요. 목록을 새로 불러왔어요." +
        (action === "revise" ? " 고치던 글은 저장되지 않았어요." : ""),
      refresh: true,
    };
  }
  if (err.status === 401) {
    return { text: "로그인이 풀렸어요. 다시 로그인한 뒤 시도해 주세요.", refresh: false };
  }
  return { text: "처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.", refresh: false };
}
