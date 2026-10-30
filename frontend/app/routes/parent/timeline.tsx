import { Link, useLoaderData, useSearchParams } from "react-router";
import { EmptyState } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import {
  getJournal,
  getParentChildren,
  getParentHome,
  getPendingInstitutionRequests,
  isReady,
  loadIfReady,
} from "@/lib/api";
import { todayDate } from "@/lib/datetime";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { Institution } from "@/lib/types";

export async function clientLoader() {
  // 서버에 아직 없다. mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("guardianJournal")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [{ child }, journal, pending] = await Promise.all([
    getParentHome(childId),
    getJournal(childId),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
  ]);
  return { child, journal, pending };
}

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function TimelinePageRoute() {
  if (!isReady("guardianJournal")) return <ParentNotReadyPage title="오늘의 일지" showChildSwitch />;
  return <TimelinePage />;
}

function TimelinePage() {
  const { child, journal, pending } = useLoaderData<typeof clientLoader>()!;
  const [searchParams, setSearchParams] = useSearchParams();
  // 기관 유형이 아니라 "이 아이에 연결된 기관 하나하나"를 기준으로 거른다 —
  // 같은 유형(예: 센터) 기관이 여러 곳이어도 섞이지 않도록.
  const filter = searchParams.get("institution") ?? "all";

  // 일지에는 시각이 없어 날짜가 유일한 시간 정보다. "오늘"은 진짜 오늘 날짜일 때만 쓴다.
  const today = todayDate();
  const now = new Date();
  const dateLabel = `${now.getMonth() + 1}월 ${now.getDate()}일 ${WEEKDAYS[now.getDay()]}요일`;

  // 명부에서 빠진 기관의 지난 일지도 보이므로, 그 기관으로도 거를 수 있어야 한다.
  // 지금 기관을 먼저(아이 목록 순서), 일지에만 남은 기관을 그 뒤에 기관 id 순으로 둔다.
  const current = child.institutions.map(({ institution }) => institution);
  const past = journal
    .map((j) => j.institution)
    .filter((inst, i, all) => all.findIndex((o) => o.id === inst.id) === i)
    .filter((inst) => !current.some((c) => c.id === inst.id))
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
  const filterInstitutions: Institution[] = [...current, ...past];

  const filtered = journal.filter(
    (j) => filter === "all" || j.institution.id === filter,
  );
  const groups = filtered.reduce<Record<string, typeof filtered>>((acc, j) => {
    const label = j.date === today ? "오늘" : j.date.slice(5).replace("-", ".");
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
        alert={(pending ?? []).length > 0}
      />

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
        {filterInstitutions.map((institution) => (
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
                  <p className="mb-3 line-clamp-2 text-[15px] leading-6">{j.content}</p>
                  <div className="flex justify-end">
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
