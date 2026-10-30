import { useLoaderData } from "react-router";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getJournal, getParentChildren, isReady } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader({ request }: { request: Request }) {
  // 서버에 아직 없다. mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("careReport")) return null;
  const url = new URL(request.url);
  const ids = (url.searchParams.get("ids") ?? "").split(",").filter(Boolean);
  const kids = await getParentChildren();
  const journal = await getJournal(readSelectedChildId(kids));
  const entries = ids
    .map((id) => journal.find((j) => j.id === id))
    .filter((j): j is NonNullable<typeof j> => Boolean(j));
  return { entries };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function ReportEvidencePageRoute() {
  if (!isReady("careReport")) return <ParentNotReadyPage title="관련 일지" back />;
  return <ReportEvidencePage />;
}

function ReportEvidencePage() {
  const { entries } = useLoaderData<typeof clientLoader>()!;

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
            <p className="text-[15px] leading-6">{e.content}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
