import { Link, useLoaderData } from "react-router";
import { TourButton } from "@/components/Tour";
import { ConsentStatus, InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadySection } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import {
  getJournal,
  getParentChildren,
  getParentHome,
  getPendingInstitutionRequests,
  loadIfReady,
  loadOpenChecklistCount,
} from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { ParentActivity } from "@/lib/types";

export async function clientLoader() {
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  // 일지·기관 요청은 스위치가 꺼져 있으면 null 로 받아 mock 을 진짜 아이 옆에 띄우지 않는다 (loadIfReady).
  const [{ child, activity }, journal, pending, openChecklists] = await Promise.all([
    getParentHome(childId),
    // 최근 2개만 보인다 — 서버가 잘라 준다 (#458)
    loadIfReady("guardianJournal", () => getJournal(childId, { limit: 2 })),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
    // 헤더 알림 점은 확인 안 한 준비물·확인사항도 본다. 못 받아도 홈은 그대로 연다 (loadOpenChecklistCount)
    loadOpenChecklistCount(childId),
  ]);
  return {
    child,
    activity,
    // 서버가 이미 2개만 준다. 그래도 한 번 더 자른다 — FE 가 먼저 배포되면 그 사이 예전 서버는 limit 를 모르고 전부 준다
    recent: journal?.slice(0, 2) ?? null,
    pending: pending ?? [],
    openChecklists: openChecklists ?? 0,
  };
}

export default function ParentHomePage() {
  const { child, activity, recent, pending, openChecklists } = useLoaderData<typeof clientLoader>();
  const pendingRequest = pending[0];

  return (
    <div className="flex flex-col gap-6">
      <ParentPageHeader
        title="오늘의 기록"
        subtitle={child.name}
        showChildSwitch
        help="home"
        alert={pending.length > 0 || openChecklists > 0}
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

      <section data-tour="news">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="text-[16px] font-bold">새 소식</h2>
          <Link to="/parent/timeline" className="text-[14px] font-semibold text-accentink">
            타임라인 전체보기 ›
          </Link>
        </div>
        {recent === null ? (
          <ParentNotReadySection />
        ) : recent.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-line2 px-4 py-6 text-center text-[14px] text-muted">
            아직 새로운 소식이 없어요.
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
                    {j.content}
                  </span>
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section data-tour="orgs">
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
            아직 공유된 활동이 없어요.
          </p>
        ) : (
          <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line">
            {activity.map((a) =>
              activityLink(a) ? (
                <li key={a.id}>
                  <Link
                    to={activityLink(a)!}
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

/** 최근 공유 활동 한 줄이 갈 곳. 일지는 그 상세, 인사이트는 한 편이면 그 상세 · 여러 편 묶음이면 리포트 탭 */
function activityLink(a: ParentActivity): string | null {
  if (a.journalId) return `/parent/journal/${a.journalId}`;
  if (a.kind === "insight") return a.insightId ? `/parent/report/${a.insightId}` : "/parent/report";
  return null;
}
