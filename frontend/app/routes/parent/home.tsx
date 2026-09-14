import { Link, useLoaderData } from "react-router";
import { ConsentStatus, InstitutionChip } from "@/components/ui";
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
  const [{ child, activity }, journal, today, pending] = await Promise.all([
    getParentHome(childId),
    getJournal(childId),
    getTodaySummary(childId),
    getPendingInstitutionRequests(childId),
  ]);
  return { child, activity, recent: journal.slice(0, 2), today, pending };
}

export default function ParentHomePage() {
  const { child, activity, recent, today, pending } = useLoaderData<typeof clientLoader>();
  const pendingRequest = pending[0];

  return (
    <div className="flex flex-col gap-6">
      <ParentPageHeader
        title="오늘의 기록"
        subtitle={child.name}
        showChildSwitch
        alert={pending.length > 0}
      />

      {pendingRequest ? (
        <Link
          to="/parent/notifications"
          className="flex items-center gap-2 rounded-2xl border border-accent/40 bg-accentsoft px-4 py-3 text-[15px] leading-6 text-accentink"
        >
          <span className="min-w-0 flex-1">
            <b className="font-semibold">{pendingRequest.institution.name}</b>가 새로 연결을
            요청했어요
          </span>
          <span aria-hidden className="font-semibold">
            확인하기 ›
          </span>
        </Link>
      ) : null}

      {today ? (
        <div className="rounded-2xl bg-accentink px-5 py-5 text-white">
          <p className="mb-2 text-[12px] font-bold tracking-[0.15em] text-white/70">TODAY</p>
          <p className="text-[17px] leading-7 font-medium">{today}</p>
        </div>
      ) : null}

      <section className="flex items-center gap-3 rounded-2xl border border-line px-4 py-4">
        <span
          aria-hidden
          className="flex size-12 items-center justify-center rounded-full bg-surface2 text-[18px] font-semibold text-ink2"
        >
          {child.name.slice(0, 1)}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[18px] font-bold">{child.name}</span>
          <span className="block truncate text-[14px] text-muted">
            <span className="tabular-nums">{child.birthDate.replaceAll("-", ".")}생</span>
            {child.school ? ` · ${child.school}` : ""}
          </span>
        </span>
      </section>

      <section>
        <div className="mb-2 flex items-center justify-between">
          <h2 className="text-[16px] font-bold">새 소식</h2>
          <Link to="/parent/timeline" className="text-[14px] font-semibold text-accentink">
            타임라인 전체보기 ›
          </Link>
        </div>
        {recent.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-line2 px-4 py-6 text-center text-[14px] text-muted">
            아직 새로운 소식이 없습니다.
          </p>
        ) : (
          <div className="flex flex-col gap-2.5">
            {recent.map((j) => (
              <Link
                key={j.id}
                to={`/parent/journal/${j.id}`}
                className="flex items-start gap-3 rounded-2xl border border-line px-4 py-3"
              >
                <InstitutionIcon type={j.institution.type} />
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold">{j.institution.name}</span>
                  <span className="line-clamp-2 block text-[14px] leading-6 text-ink2">
                    {j.summary}
                  </span>
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">연결된 기관</h2>
        <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line">
          {child.institutions.map(({ institution, consent }) => (
            <li key={institution.id}>
              <Link
                to={`/parent/timeline?institution=${institution.id}`}
                className="tap flex flex-wrap items-center gap-3 px-4 py-3 hover:bg-surface2"
              >
                <InstitutionIcon type={institution.type} />
                <InstitutionChip institution={institution} withName />
                <span className="ml-auto flex items-center gap-2">
                  <ConsentStatus state={consent} />
                  <span aria-hidden className="text-muted">
                    ›
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">최근 공유 활동</h2>
        {activity.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-line2 px-4 py-6 text-center text-[14px] text-muted">
            아직 공유된 활동이 없습니다.
          </p>
        ) : (
          <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line">
            {activity.map((a) =>
              a.journalId ? (
                <li key={a.id}>
                  <Link
                    to={`/parent/journal/${a.journalId}`}
                    className="tap block px-4 py-3 hover:bg-surface2"
                  >
                    <p className="text-[15px] leading-6 text-accentink underline">{a.text}</p>
                    <p className="mt-0.5 text-[13px] text-muted">{a.at}</p>
                  </Link>
                </li>
              ) : (
                <li key={a.id} className="px-4 py-3">
                  <p className="text-[15px] leading-6">{a.text}</p>
                  <p className="mt-0.5 text-[13px] text-muted">{a.at}</p>
                </li>
              ),
            )}
          </ul>
        )}
      </section>

      <p className="text-[14px] leading-6 text-muted">
        궁금한 점은{" "}
        <Link to="/parent/settings" className="text-accentink underline">
          설정
        </Link>
        에서 언제든 확인할 수 있어요.
      </p>
    </div>
  );
}
