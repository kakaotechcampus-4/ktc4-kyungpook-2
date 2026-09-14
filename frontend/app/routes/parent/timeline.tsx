import { Link, useLoaderData, useSearchParams } from "react-router";
import { EmptyState } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import {
  getJournal,
  getParentChildren,
  getParentHome,
  getPendingInstitutionRequests,
  getTodaySummary,
} from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader() {
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [{ child }, journal, today, pending] = await Promise.all([
    getParentHome(childId),
    getJournal(childId),
    getTodaySummary(childId),
    getPendingInstitutionRequests(childId),
  ]);
  return { child, journal, today, pending };
}

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

export default function TimelinePage() {
  const { child, journal, today, pending } = useLoaderData<typeof clientLoader>();
  const [searchParams, setSearchParams] = useSearchParams();
  // 기관 유형이 아니라 "이 아이에 연결된 기관 하나하나"를 기준으로 거른다 —
  // 같은 유형(예: 센터) 기관이 여러 곳이어도 섞이지 않도록.
  const filter = searchParams.get("institution") ?? "all";

  const latest = journal[0];
  const latestDate = latest ? new Date(latest.date) : new Date();
  const dateLabel = `${latestDate.getMonth() + 1}월 ${latestDate.getDate()}일 ${WEEKDAYS[latestDate.getDay()]}요일`;

  const filtered = journal.filter(
    (j) => filter === "all" || j.institution.id === filter,
  );
  const groups = filtered.reduce<Record<string, typeof filtered>>((acc, j) => {
    const label = j.date === latest?.date ? "오늘" : j.date.slice(5).replace("-", ".");
    (acc[label] ??= []).push(j);
    return acc;
  }, {});

  const setFilter = (id: string) => {
    if (id === "all") {
      setSearchParams({}, { replace: true });
    } else {
      setSearchParams({ institution: id }, { replace: true });
    }
  };

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="오늘의 일지"
        subtitle={`${child.name} · ${dateLabel}`}
        showChildSwitch
        alert={pending.length > 0}
      />

      {today ? (
        <div className="rounded-2xl bg-accentink px-5 py-5 text-white">
          <p className="mb-2 text-[12px] font-bold tracking-[0.15em] text-white/70">TODAY</p>
          <p className="text-[17px] leading-7 font-medium">{today}</p>
        </div>
      ) : null}

      <div className="-mx-4 flex gap-2 overflow-x-auto px-4">
        <button
          onClick={() => setFilter("all")}
          className={`tap shrink-0 rounded-2xl px-4 text-[14px] font-semibold ${
            filter === "all"
              ? "bg-accent text-white"
              : "border border-line2 text-ink2 hover:bg-surface2"
          }`}
        >
          전체
        </button>
        {child.institutions.map(({ institution }) => (
          <button
            key={institution.id}
            onClick={() => setFilter(institution.id)}
            className={`tap shrink-0 rounded-2xl px-4 text-[14px] font-semibold ${
              filter === institution.id
                ? "bg-accent text-white"
                : "border border-line2 text-ink2 hover:bg-surface2"
            }`}
          >
            {institution.name}
          </button>
        ))}
      </div>

      {filtered.length === 0 ? (
        <EmptyState icon="✉" title="아직 새로운 소식이 없습니다" description="기관에서 일지를 올리면 바로 알려드립니다." />
      ) : (
        Object.entries(groups).map(([label, entries]) => (
          <section key={label}>
            <h2 className="mb-2 text-[15px] font-bold text-ink2">{label}</h2>
            <div className="flex flex-col gap-3">
              {entries.map((j) => (
                <div key={j.id} className="rounded-2xl border border-line px-4 py-4">
                  <div className="mb-2 flex items-center gap-2.5">
                    <InstitutionIcon type={j.institution.type} />
                    <span className="font-bold">{j.institution.name}</span>
                    {j.isNew ? (
                      <span className="ml-auto shrink-0 rounded-full bg-accentsoft px-2.5 py-1 text-[12px] font-bold text-accentink">
                        새소식
                      </span>
                    ) : null}
                  </div>
                  <p className="mb-3 text-[15px] leading-6">{j.summary}</p>
                  <div className="flex items-center justify-between">
                    <span className="rounded-full bg-surface2 px-2.5 py-1 text-[12px] font-semibold text-ink2">
                      {j.tag}
                    </span>
                    <Link
                      to={`/parent/journal/${j.id}`}
                      className="text-[14px] font-semibold text-accentink"
                    >
                      자세히 보기 ›
                    </Link>
                  </div>
                </div>
              ))}
            </div>
          </section>
        ))
      )}
    </div>
  );
}
