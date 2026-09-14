import { useLoaderData } from "react-router";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getJournal, getParentChildren } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader({ request }: { request: Request }) {
  const url = new URL(request.url);
  const ids = (url.searchParams.get("ids") ?? "").split(",").filter(Boolean);
  const kids = await getParentChildren();
  const journal = await getJournal(readSelectedChildId(kids));
  const entries = ids
    .map((id) => journal.find((j) => j.id === id))
    .filter((j): j is NonNullable<typeof j> => Boolean(j));
  return { entries };
}

export default function ReportEvidencePage() {
  const { entries } = useLoaderData<typeof clientLoader>();

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="관련 일지" subtitle={`리포트 근거 ${entries.length}건`} back />

      <p className="text-[15px] leading-7 text-ink2">
        이 내용은 아래 일지를 바탕으로 정리했습니다.
      </p>

      <div className="flex flex-col gap-3">
        {entries.map((e) => (
          <div key={e.id} className="rounded-2xl border border-line px-4 py-4">
            <p className="mb-1.5 font-mono text-[13px] font-bold tabular-nums text-muted">
              {e.date.slice(5).replace("-", ".")} <span className="text-ink">{e.institution.name}</span>
            </p>
            <p className="text-[15px] leading-6">{e.summary}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
