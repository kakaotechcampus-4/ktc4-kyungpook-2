import { Card, EmptyState, Note, PageHeader, ValidationBadge } from "@/components/ui";
import { getBlockedQueue } from "@/lib/api";

export default async function ReinputQueuePage() {
  const items = await getBlockedQueue();

  return (
    <>
      <PageHeader
        title="재입력 요청 큐"
        description="검증에서 멈춘 기록입니다. 원본을 수정한 뒤 다시 올려주세요"
      />

      {items.length === 0 ? (
        <EmptyState icon="✓" title="멈춘 기록이 없습니다" />
      ) : (
        <div className="flex flex-col gap-4">
          {items.map((item) => (
            <Card key={item.id}>
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <ValidationBadge status="BLOCK" />
                <span className="text-[15px] font-semibold">{item.record.fileName}</span>
                <span className="text-[14px] text-muted">
                  {item.childName ? `${item.childName} · ` : ""}
                  {item.record.type}
                </span>
              </div>

              <p className="mb-3 rounded border border-block/30 bg-blocksoft px-3 py-2 text-[15px] text-block">
                <b className="font-semibold">멈춘 이유</b> · {item.violationReason}
              </p>

              <blockquote className="mb-4 rounded bg-surface2 px-3 py-2.5 text-[15px] leading-7 text-ink2">
                {item.record.preview}
              </blockquote>

              <div className="flex flex-wrap gap-2">
                <button className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink">
                  수정한 원본 다시 올리기
                </button>
                <button className="tap rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2">
                  이 기록 보류
                </button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <div className="mt-5">
        <Note>
          원본은 수정하거나 삭제하지 않습니다. 고친 내용을 <b className="font-semibold">새
          기록으로 추가</b>합니다 (append-only).
        </Note>
      </div>
    </>
  );
}
