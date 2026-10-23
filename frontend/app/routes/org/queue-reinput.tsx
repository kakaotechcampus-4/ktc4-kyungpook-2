import { useState } from "react";
import { useLoaderData, useNavigate, useRevalidator } from "react-router";
import { Card, EmptyState, Note, PageHeader, ValidationBadge } from "@/components/ui";
import { ApiError, getBlockedQueue, resolveBlockedItem } from "@/lib/api";
import type { BlockedItem } from "@/lib/types";

type Action = "reupload" | "hold";

export async function clientLoader() {
  return { items: await getBlockedQueue() };
}

export default function ReinputQueuePage() {
  const { items } = useLoaderData<typeof clientLoader>();

  return (
    <>
      <PageHeader
        title="수정 요청"
        description="검증에서 멈춘 기록입니다. 원본을 수정한 뒤 다시 올려주세요"
      />

      {items.length === 0 ? (
        <EmptyState icon="✓" title="멈춘 기록이 없습니다" />
      ) : (
        <div className="flex flex-col gap-4">
          {items.map((item) => (
            <BlockedCard key={item.id} item={item} />
          ))}
        </div>
      )}

      <div className="mt-5">
        <Note>
          원본은 수정하거나 삭제하지 않습니다. 고친 내용을{" "}
          <b className="font-semibold">새 기록으로 추가</b>합니다 (append-only).
        </Note>
      </div>
    </>
  );
}

function BlockedCard({ item }: { item: BlockedItem }) {
  const revalidator = useRevalidator();
  const navigate = useNavigate();
  const [busy, setBusy] = useState<Action | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function resolve(action: Action) {
    setBusy(action);
    setError(null);
    try {
      await resolveBlockedItem(item.id, action);
      // 서버는 "다시 올리겠다"는 표시만 남긴다. 고친 파일은 등록 화면에서 새로 올린다
      if (action === "reupload") navigate("/upload");
      else await revalidator.revalidate();
    } catch (err) {
      setError(messageOf(err));
      // 이미 처리됐거나 없어진 건이면 목록을 새로 받아 화면을 서버와 맞춘다
      if (err instanceof ApiError && (err.status === 400 || err.status === 404)) {
        await revalidator.revalidate();
      }
    } finally {
      setBusy(null);
    }
  }

  /*
   * 막힌 문장이 60자 뒤에 있으면 preview 로는 무엇을 고쳐야 할지 안 보인다.
   * 전체 본문을 펼쳐 두고, 길면 카드 안에서 스크롤한다 (확인 필요 큐와 같은 방식, #112).
   */
  const content = item.record.fullContent ?? item.record.preview;
  const meta = [item.childName, item.record.type, formatDate(item.record.capturedAt)].filter(
    Boolean,
  );

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

      {content ? (
        <blockquote
          tabIndex={0}
          aria-label="기록 본문"
          className="mb-4 max-h-72 overflow-y-auto rounded bg-surface2 px-3 py-2.5 text-[15px] leading-7 whitespace-pre-wrap text-ink2"
        >
          {content}
        </blockquote>
      ) : null}
      {item.record.fullContent == null ? (
        <p role="status" className="mb-4 rounded bg-reviewsoft px-3 py-2 text-[14px] text-review">
          {item.record.preview
            ? "전체 본문을 불러오지 못해 앞부분 미리보기만 표시합니다."
            : "본문을 불러오지 못했습니다. 파일 이름과 날짜로 원본을 찾아 확인해 주세요."}
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <button
          onClick={() => resolve("reupload")}
          disabled={busy !== null}
          className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:opacity-60"
        >
          {busy === "reupload" ? "처리 중…" : "수정한 원본 다시 올리기"}
        </button>
        <button
          onClick={() => resolve("hold")}
          disabled={busy !== null}
          className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-60"
        >
          {busy === "hold" ? "처리 중…" : "이 기록 보류"}
        </button>
      </div>
      <p className="mt-2 text-[13px] text-muted">
        다시 올리기를 누르면 이 기록은 목록에서 빠지고 기록 등록 화면으로 이동합니다.
      </p>

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

function messageOf(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (err.status === 400 || err.status === 404) {
    return "이미 처리됐거나 없어진 기록입니다. 목록을 새로 불러왔습니다.";
  }
  return "처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";
}
