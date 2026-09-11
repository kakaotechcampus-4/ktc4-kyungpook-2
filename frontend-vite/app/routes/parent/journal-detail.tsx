import { useEffect, useState } from "react";
import { ImageIcon } from "lucide-react";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { flagJournalEntry, getJournal, getJournalEntry } from "@/lib/api";

export async function clientLoader({ params }: { params: { id: string } }) {
  const entry = await getJournalEntry(params.id);
  if (!entry) throw new Response("Not Found", { status: 404 });

  // "이전 일지"/"다음 일지" — 날짜·시간 오름차순으로 봤을 때 앞뒤 항목
  const journal = await getJournal(entry.childId);
  const ascending = [...journal].sort((a, b) =>
    `${a.date}${a.time}`.localeCompare(`${b.date}${b.time}`),
  );
  const idx = ascending.findIndex((j) => j.id === entry.id);
  const prevId = idx > 0 ? ascending[idx - 1].id : null;
  const nextId = idx !== -1 && idx < ascending.length - 1 ? ascending[idx + 1].id : null;

  return { entry, prevId, nextId };
}

export default function JournalDetailPage() {
  const { entry, prevId, nextId } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  const [flagging, setFlagging] = useState(false);
  const [toast, setToast] = useState(false);

  useEffect(() => setToast(false), [entry.id]);

  async function flag() {
    setFlagging(true);
    try {
      await flagJournalEntry(entry.id);
      setToast(true);
      revalidator.revalidate();
      setTimeout(() => setToast(false), 3000);
    } finally {
      setFlagging(false);
    }
  }

  const navBtn =
    "tap flex h-12 items-center justify-center rounded-2xl border text-[14px] font-semibold";

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="일지 상세" subtitle={entry.institution.name} back />

      <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
        <InstitutionIcon type={entry.institution.type} />
        <span>
          <span className="block font-bold">{entry.institution.name}</span>
          <span className="block text-[13px] text-muted">
            <span className="tabular-nums">{entry.date.slice(5).replace("-", ".")}</span>{" "}
            <span className="tabular-nums">{entry.time}</span>
          </span>
        </span>
      </div>

      <section>
        <p className="mb-2 text-[13px] font-bold text-accentink">오늘 있었던 일</p>
        <p className="text-[16px] leading-7">{entry.detail}</p>
      </section>

      {entry.photoCount ? (
        <section>
          <h2 className="mb-2 text-[15px] font-bold">사진</h2>
          <div className="grid grid-cols-3 gap-2">
            {Array.from({ length: entry.photoCount }, (_, i) => (
              <div
                key={i}
                className="flex aspect-square items-center justify-center rounded-2xl bg-surface2 text-muted"
              >
                <ImageIcon size={22} strokeWidth={1.5} />
              </div>
            ))}
          </div>
        </section>
      ) : null}

      {entry.institutionNote ? (
        <section className="rounded-2xl bg-surface2 px-4 py-4">
          <p className="mb-1.5 text-[13px] font-bold text-accentink">기관에서 남긴 말</p>
          <p className="text-[15px] leading-6">{entry.institutionNote}</p>
        </section>
      ) : null}

      {entry.flagged ? (
        <p className="tap flex h-14 w-full items-center justify-center rounded-2xl bg-surface2 text-[15px] font-bold text-ink2">
          {entry.institution.name}에 확인 요청함
        </p>
      ) : (
        <button
          onClick={flag}
          disabled={flagging}
          className="tap h-14 w-full rounded-2xl border border-line2 text-[15px] font-bold text-ink2 hover:bg-surface2 disabled:opacity-50"
        >
          {flagging ? "요청 중…" : "이 내용이 이상해요"}
        </button>
      )}

      <div className="grid grid-cols-2 gap-2">
        {prevId ? (
          <Link to={`/parent/journal/${prevId}`} className={`${navBtn} border-line2 text-ink2 hover:bg-surface2`}>
            이전 일지
          </Link>
        ) : (
          <span aria-disabled className={`${navBtn} border-line2 text-muted opacity-50`}>
            이전 일지
          </span>
        )}
        {nextId ? (
          <Link to={`/parent/journal/${nextId}`} className={`${navBtn} border-line2 text-ink2 hover:bg-surface2`}>
            다음 일지
          </Link>
        ) : (
          <span aria-disabled className={`${navBtn} border-line2 text-muted opacity-50`}>
            다음 일지
          </span>
        )}
      </div>

      {toast ? (
        <div
          role="status"
          className="fixed bottom-24 left-1/2 z-30 w-[calc(100%-2rem)] max-w-[398px] -translate-x-1/2 rounded-2xl bg-ink px-4 py-3 text-center text-[14px] font-medium text-white shadow-lg md:max-w-[528px] lg:max-w-[608px]"
        >
          {entry.institution.name}에 이 일지를 다시 확인해달라고 전했습니다.
        </div>
      ) : null}
    </div>
  );
}
