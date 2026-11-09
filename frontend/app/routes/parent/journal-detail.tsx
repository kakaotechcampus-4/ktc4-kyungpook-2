import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Link, useLoaderData } from "react-router";
import { getJournalEntry, isReady } from "@/lib/api";

export async function clientLoader({ params }: { params: { id: string } }) {
  if (!isReady("guardianJournal")) return null;
  const entry = await getJournalEntry(params.id);
  if (!entry) throw new Response("Not Found", { status: 404 });

  return { entry, prevId: entry.prevId ?? null, nextId: entry.nextId ?? null, sameDay: entry.sameDay ?? [] };
}

export default function JournalDetailPageRoute() {
  if (!isReady("guardianJournal")) return <ParentNotReadyPage title="일지 상세" back />;
  return <JournalDetailPage />;
}

function JournalDetailPage() {
  const { entry, prevId, nextId, sameDay } = useLoaderData<typeof clientLoader>()!;

  const navBtn =
    "tap flex h-12 items-center justify-center rounded-2xl border text-[14px] font-semibold";

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="일지 상세" subtitle={entry.institution.name} back />

      <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
        <InstitutionIcon type={entry.institution.type} />
        <span>
          <span className="block font-bold">{entry.institution.name}</span>
          <span className="block text-[13px] text-muted tabular-nums">
            {entry.date.slice(5).replace("-", ".")}
          </span>
        </span>
      </div>

      <section>
        <p className="mb-2 text-[13px] font-bold text-accentink">있었던 일</p>
        <p className="text-[16px] leading-7 whitespace-pre-line">{entry.content}</p>
      </section>

      {sameDay.length > 0 ? (
        <section>
          <p className="mb-2 text-[13px] font-bold text-accentink">같은 날 다른 곳에서는</p>
          <ul className="flex flex-col gap-2">
            {sameDay.map((j) => (
              <li key={j.id}>
                <Link
                  to={`/parent/journal/${j.id}`}
                  replace
                  className="flex items-start gap-3 rounded-2xl border border-line px-4 py-3 hover:bg-surface2"
                >
                  <InstitutionIcon type={j.institution.type} />
                  <span className="min-w-0">
                    <span className="block font-bold">{j.institution.name}</span>
                    <span className="mt-0.5 line-clamp-2 block text-[14px] leading-6 text-ink2">
                      {j.content}
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className="grid grid-cols-2 gap-2">
        {prevId ? (
          <Link
            to={`/parent/journal/${prevId}`}
            replace
            className={`${navBtn} border-line2 text-ink2 hover:bg-surface2`}
          >
            이전 일지
          </Link>
        ) : (
          <span aria-disabled className={`${navBtn} border-line2 text-muted opacity-50`}>
            이전 일지
          </span>
        )}
        {nextId ? (
          <Link
            to={`/parent/journal/${nextId}`}
            replace
            className={`${navBtn} border-line2 text-ink2 hover:bg-surface2`}
          >
            다음 일지
          </Link>
        ) : (
          <span aria-disabled className={`${navBtn} border-line2 text-muted opacity-50`}>
            다음 일지
          </span>
        )}
      </div>
    </div>
  );
}
