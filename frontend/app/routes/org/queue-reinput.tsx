import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import { Busy, Card, EmptyState, PageHeader, ValidationBadge } from "@/components/ui";
import {
  ApiError,
  getBlockedQueue,
  MAX_REVISED_CONTENT,
  resolveBlockedItem,
  reviseBlockedItem,
} from "@/lib/api";
import type { BlockedItem } from "@/lib/types";

type Action = "revise" | "hold";

export async function clientLoader() {
  return { items: await getBlockedQueue() };
}

export default function ReinputQueuePage() {
  const { items } = useLoaderData<typeof clientLoader>();
  // 고친 카드는 큐에서 빠져 사라진다. 무엇이 됐는지 페이지에 남겨 둔다
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <>
      <PageHeader
        title="검증 수정 요청"
      />

      {notice ? (
        <p role="status" className="mb-4 rounded bg-passsoft px-3 py-2 text-[15px] text-pass">
          {notice}
        </p>
      ) : null}

      {items.length === 0 ? (
        <EmptyState icon="done" title="멈춘 기록이 없습니다" />
      ) : (
        <div className="flex flex-col gap-4">
          {items.map((item) => (
            <BlockedCard key={item.id} item={item} onNotice={setNotice} />
          ))}
        </div>
      )}
    </>
  );
}

function BlockedCard({
  item,
  onNotice,
}: {
  item: BlockedItem;
  /** 페이지 위 안내. 다른 처리를 시작하면 지난 안내를 지운다 */
  onNotice: (message: string | null) => void;
}) {
  const revalidator = useRevalidator();
  const [busy, setBusy] = useState<Action | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);

  /*
   * 막힌 문장이 60자 뒤에 있으면 preview 로는 무엇을 고쳐야 할지 안 보인다.
   * 전체 본문을 펼쳐 두고, 길면 카드 안에서 스크롤한다 (확인 필요 큐와 같은 방식, #112).
   */
  const content = item.record.fullContent ?? item.record.preview;
  const [draft, setDraft] = useState(item.record.fullContent ?? "");
  const meta = [item.childName, item.record.type, formatDate(item.record.capturedAt)].filter(
    Boolean,
  );

  // 서버도 같은 이유로 거절한다 — 안 고친 채 다시 돌려 판정이 바뀌길 기다리지 않게 (#357)
  const unchanged = draft.trim() === (item.record.fullContent ?? "").trim();
  const canSave = busy === null && draft.trim() !== "" && !unchanged;

  async function run(action: Action) {
    setBusy(action);
    setError(null);
    onNotice(null);
    try {
      if (action === "revise") {
        await reviseBlockedItem(item.id, draft);
        onNotice("다시 검사를 시작했어요. 통과하면 오늘의 업무에서 요약할 수 있어요.");
      } else {
        await resolveBlockedItem(item.id, "hold");
      }
      await revalidator.revalidate();
    } catch (err) {
      setError(messageOf(err, action));
      // 이미 처리됐거나 없어진 건이면 목록을 새로 받아 화면을 서버와 맞춘다
      if (err instanceof ApiError && (err.status === 400 || err.status === 404)) {
        await revalidator.revalidate();
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
        <p role="alert" className="mt-3 text-[14px] text-block">
          {error}
        </p>
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

function messageOf(err: unknown, action: Action): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (action === "revise" && err.status === 400) {
    // 본문 형식 문제 — 카드는 그대로 남아 있다
    if (err.code === "INVALID_REQUEST") return "본문이 비어 있거나 너무 길어요. 고친 내용을 확인해 주세요.";
    // 이미 처리됐거나 다른 선생님이 먼저 고친 기록은 목록을 새로 받으면 카드가 바뀌어 이 문구가 남지 않는다.
    // 카드가 그대로라면 아이가 명단에서 빠졌거나 공유 동의가 꺼져 다시 검사할 수 없는 기록이다
    return "다시 검사할 수 없는 기록이에요. 아이가 명단에서 빠졌거나 공유 동의가 꺼졌을 수 있어요. 이 기록은 보류해 주세요.";
  }
  if (err.status === 400 || err.status === 404) {
    return "이미 처리됐거나 없어진 기록입니다. 목록을 새로 불러왔습니다.";
  }
  return "처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";
}
