import { useState } from "react";
import { useLoaderData, useRevalidator } from "react-router";
import { Card, ConfidenceWarning, EmptyState, Note, PageHeader } from "@/components/ui";
import { getMatchingQueue, resolveMatchingItem } from "@/lib/api";
import type { MatchingItem } from "@/lib/types";

export async function clientLoader() {
  return { items: await getMatchingQueue() };
}

const STATUS_TITLE: Record<MatchingItem["status"], string> = {
  multi: "이 기록에 해당하는 아이를 선택하세요",
  unmatched: "일치하는 아이를 찾지 못했습니다",
  low: "이 기록에 해당하는 아이를 확인하세요",
};

export default function MatchingQueuePage() {
  const { items } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [index, setIndex] = useState(0);
  const [selectedChild, setSelectedChild] = useState<string | null>(null);
  const remaining = items;
  const item = remaining[Math.min(index, remaining.length - 1)];

  async function resolve() {
    if (!item) return;
    await resolveMatchingItem(item.id);
    setSelectedChild(null);
    setIndex(0);
    revalidator.revalidate();
  }

  return (
    <>
      <PageHeader
        title="확인이 필요한 기록 · 아이별 정리"
        description="AI가 아이를 확정하지 못한 기록을 사람이 지정합니다"
      />

      {!item ? (
        <EmptyState
          icon="✓"
          title="확인이 필요한 기록이 없습니다"
          description="AI가 확신하지 못한 기록이 생기면 여기로 옵니다."
        />
      ) : (
        <>
          <div className="mb-4 flex items-center justify-between">
            <p className="text-[15px] text-muted tabular-nums">
              남은 기록 <b className="font-semibold text-ink">{remaining.length}</b>건
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setIndex((i) => Math.max(0, i - 1))}
                disabled={index === 0}
                className="tap rounded border border-line2 px-3 text-[15px] disabled:opacity-40"
              >
                이전
              </button>
              <button
                onClick={() => setIndex((i) => Math.min(remaining.length - 1, i + 1))}
                disabled={index >= remaining.length - 1}
                className="tap rounded border border-line2 px-3 text-[15px] disabled:opacity-40"
              >
                다음
              </button>
            </div>
          </div>

          <Card>
            <div className="mb-5 rounded bg-surface2 p-4">
              <p className="mb-1.5 text-[13px] font-semibold tracking-wider text-muted uppercase">
                기록 미리보기
              </p>
              <p className="mb-2 text-[15px] font-semibold">{item.record.fileName}</p>
              <p className="mb-3 text-[16px] leading-7">{item.record.preview}</p>
              <p className="text-[13px] text-muted">
                유형 · {item.record.type} &nbsp;|&nbsp; 기록 시각 ·{" "}
                {new Date(item.record.capturedAt).toLocaleString("ko-KR", {
                  month: "2-digit",
                  day: "2-digit",
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </p>
            </div>

            <h2 className="mb-3 text-[17px] font-bold">
              {STATUS_TITLE[item.status]}
              {item.candidates.length > 0 ? (
                <span className="ml-2 text-[15px] font-medium text-muted">
                  (후보 {item.candidates.length}명)
                </span>
              ) : null}
            </h2>

            <div className="mb-4">
              {item.status === "unmatched" ? (
                <ConfidenceWarning message="이름이 기록에 없거나 인식되지 않았습니다" />
              ) : (
                <ConfidenceWarning confidence={item.confidence} />
              )}
            </div>

            {item.candidates.length > 0 ? (
              <fieldset className="mb-4 flex flex-col gap-2">
                <legend className="sr-only">후보 아이 선택</legend>
                {item.candidates.map((c) => (
                  <label
                    key={c.childId}
                    className="tap flex cursor-pointer items-center gap-3 rounded border border-line2 px-3 hover:border-accent has-checked:border-accent has-checked:bg-accentsoft"
                  >
                    <input
                      type="radio"
                      name={`cand-${item.id}`}
                      className="size-4"
                      checked={selectedChild === c.childId}
                      onChange={() => setSelectedChild(c.childId)}
                    />
                    <span
                      aria-hidden
                      className="flex size-8 items-center justify-center rounded-full bg-surface2 text-[14px] font-semibold text-ink2"
                    >
                      {c.name.slice(0, 1)}
                    </span>
                    <span>
                      <span className="block text-[16px] font-semibold">{c.name}</span>
                      <span className="block text-[13px] text-muted">{c.group}</span>
                    </span>
                  </label>
                ))}
              </fieldset>
            ) : (
              <label className="mb-4 flex flex-col gap-1.5">
                <span className="text-[15px] font-semibold">아이 이름으로 직접 찾기</span>
                <input
                  placeholder="이름 입력"
                  className="tap rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
                />
              </label>
            )}

            <div className="mb-4">
              <Note>
                낮은 확신도의 기록은 자동으로 확정되지 않습니다. 사람이 직접 아이를
                선택해주세요.
              </Note>
            </div>

            <div className="flex flex-wrap gap-2">
              <button
                onClick={resolve}
                disabled={item.candidates.length > 0 && !selectedChild}
                className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink disabled:cursor-not-allowed disabled:bg-line2 disabled:text-muted"
              >
                {item.status === "low" ? "맞습니다 · 확정" : "선택한 아이로 확정"}
              </button>
              <button
                onClick={resolve}
                className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
              >
                이 기관 아동 아님
              </button>
            </div>
          </Card>
        </>
      )}
    </>
  );
}
