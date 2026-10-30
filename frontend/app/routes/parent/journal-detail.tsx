import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Link, useLoaderData } from "react-router";
import { getJournal, getJournalEntry, isReady } from "@/lib/api";

export async function clientLoader({ params }: { params: { id: string } }) {
  // 서버에 아직 없다. mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("guardianJournal")) return null;
  // 볼 수 없는 일지(없음·남의 아이·끊긴 연결)는 undefined 로 온다 → "페이지를 찾을 수 없습니다"
  const entry = await getJournalEntry(params.id);
  if (!entry) throw new Response("Not Found", { status: 404 });

  // "이전 일지"/"다음 일지" — 서버 목록 순서(최근 순) 그대로. 일지에는 시각이 없어 화면이 다시 정렬하지 않는다.
  // 앞 칸이 더 최근(다음), 뒤 칸이 더 예전(이전)이다.
  const journal = await getJournal(entry.childId);
  const idx = journal.findIndex((j) => j.id === entry.id);
  const nextId = idx > 0 ? journal[idx - 1].id : null;
  const prevId = idx !== -1 && idx < journal.length - 1 ? journal[idx + 1].id : null;

  return { entry, prevId, nextId };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function JournalDetailPageRoute() {
  if (!isReady("guardianJournal")) return <ParentNotReadyPage title="일지 상세" back />;
  return <JournalDetailPage />;
}

function JournalDetailPage() {
  const { entry, prevId, nextId } = useLoaderData<typeof clientLoader>()!;

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
    </div>
  );
}
