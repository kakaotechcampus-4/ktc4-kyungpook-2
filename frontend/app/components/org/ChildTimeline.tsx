import { useEffect, useState } from "react";
import { Spinner } from "@/components/ui";
import { getTimeline } from "@/lib/api";
import type { TimelineEntry } from "@/lib/types";

export function ChildTimeline({ childId }: { childId: string }) {
  const [list, setList] = useState<TimelineEntry[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    getTimeline(childId).then(setList, () => setFailed(true));
  }, [childId]);

  if (failed) return <p className="text-[15px] text-block">기록을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.</p>;
  if (!list) {
    return (
      <p className="flex items-center gap-2 text-[15px] text-muted">
        <Spinner size={16} /> 불러오는 중…
      </p>
    );
  }
  if (list.length === 0) return <p className="text-[15px] text-muted">요약 검토에서 승인한 기록이 아직 없어요</p>;

  return (
    <ul className="grid">
      {list.map((e) => (
        <li key={e.id} className="flex gap-4 border-t border-line py-3 first:border-t-0">
          <b className="w-12 shrink-0 text-[15px] tabular-nums">{e.date.slice(5).replace("-", "/")}</b>
          <div className="min-w-0 flex-1">
            {e.edited ? (
              <span className="mb-1 inline-block rounded bg-humansoft px-1.5 text-[12px] font-semibold text-human">선생님 수정</span>
            ) : null}
            <p className="text-[15px] leading-7 whitespace-pre-line">{e.content}</p>
          </div>
        </li>
      ))}
    </ul>
  );
}
