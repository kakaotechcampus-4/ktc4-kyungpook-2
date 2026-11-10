import { ChevronRight } from "lucide-react";
import { useState } from "react";
import { Link, useLoaderData } from "react-router";
import { EmptyState, InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import {
  getGuardianInsights,
  getParentChildren,
  getPendingInstitutionRequests,
  isReady,
  loadIfReady,
  loadOpenChecklistCount,
} from "@/lib/api";
import { formatDateMonthDay } from "@/lib/datetime";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { GuardianInsight, Institution } from "@/lib/types";

export async function clientLoader() {
  if (!isReady("guardianInsights")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [insights, pending, openChecklists] = await Promise.all([
    getGuardianInsights(childId),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
    loadOpenChecklistCount(childId),
  ]);
  const child = kids.find((k) => k.id === childId);
  return {
    childName: child?.name ?? "",
    insights: insights.filter((i) => i.childId === childId),
    pending,
    openChecklists: openChecklists ?? 0,
  };
}

export default function ReportPageRoute() {
  if (!isReady("guardianInsights")) return <ParentNotReadyPage title="리포트" showChildSwitch />;
  return <ReportPage />;
}

type Week = { period: string; from: string; to: string; items: GuardianInsight[] };
type Month = { key: string; label: string; weeks: Week[]; count: number; unread: number; institutions: Institution[] };

function groupByMonth(insights: GuardianInsight[]): Month[] {
  const months: Month[] = [];
  for (const insight of insights) {
    const key = insight.periodFrom.slice(0, 7);
    let month = months.find((m) => m.key === key);
    if (!month) {
      const [year, mm] = key.split("-");
      month = { key, label: `${year}년 ${Number(mm)}월`, weeks: [], count: 0, unread: 0, institutions: [] };
      months.push(month);
    }
    let week = month.weeks.find((w) => w.period === insight.period);
    if (!week) {
      week = { period: insight.period, from: insight.periodFrom, to: insight.periodTo, items: [] };
      month.weeks.push(week);
    }
    week.items.push(insight);
    if (insight.kind === "quiet") continue;
    month.count += 1;
    month.unread += insight.readAt ? 0 : 1;
    for (const institution of [insight.from, ...insight.evidence.map((e) => e.institution)]) {
      if (institution && !month.institutions.some((i) => i.id === institution.id)) {
        month.institutions.push(institution);
      }
    }
  }
  return months;
}

function weekLabel(week: Week): string {
  const order = week.period.match(/(\d+)주차/)?.[1];
  const sameMonth = week.from.slice(0, 7) === week.to.slice(0, 7);
  const to = sameMonth ? `${Number(week.to.slice(8, 10))}일` : formatDateMonthDay(week.to);
  return `${order ? `${order}주 · ` : ""}${formatDateMonthDay(week.from)} ~ ${to}`;
}

function ReportPage() {
  const { childName, insights, pending, openChecklists } = useLoaderData<typeof clientLoader>()!;
  const months = groupByMonth(insights);
  const [opened, setOpened] = useState<string[]>([]);
  const latest = months[0]?.key;
  const isOpen = (key: string) => key === latest || opened.includes(key);

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="리포트"
        subtitle={childName}
        showChildSwitch
        alert={(pending ?? []).length > 0 || openChecklists > 0}
      />

      {months.length === 0 ? (
        <EmptyState
          icon="none"
          title="아직 받은 리포트가 없어요"
          description="기관들이 일주일 기록을 함께 살펴 정리하면 여기에 도착해요."
        />
      ) : (
        months.map((month, index) => (
          <section key={month.key}>
            {index === 1 ? <h2 className="mb-2 text-[15px] font-bold text-ink2">지난 달</h2> : null}

            {isOpen(month.key) ? (
              <>
                <h2 className="mb-2 text-[15px] font-bold text-ink2">{month.label}</h2>
                {month.count > 0 ? <MonthSummary month={month} /> : null}
                <div className="mt-4 flex flex-col gap-4">
                  {month.weeks.map((week) => (
                    <div key={week.period}>
                      <p className="mb-2 text-[13px] text-muted">{weekLabel(week)}</p>
                      <div className="flex flex-col gap-3">
                        {week.items.map((insight) =>
                          insight.kind === "quiet" ? (
                            <QuietCard key={insight.id} insight={insight} />
                          ) : (
                            <ReportCard key={insight.id} insight={insight} />
                          ),
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <button
                type="button"
                onClick={() => setOpened((keys) => [...keys, month.key])}
                aria-expanded={false}
                className="flex w-full items-center gap-3 rounded-2xl border border-line px-4 py-3 text-left hover:bg-surface2"
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-bold">{month.label}</span>
                  <span className="block text-[13px] text-muted">
                    {month.count > 0 ? `리포트 ${month.count}개` : "특이사항 없음"}
                  </span>
                </span>
                <ChevronRight aria-hidden className="size-4 shrink-0 text-muted" />
              </button>
            )}
          </section>
        ))
      )}
    </div>
  );
}

function MonthSummary({ month }: { month: Month }) {
  return (
    <div className="rounded-2xl bg-surface2 px-4 py-4">
      <p className="text-[15px] font-bold">
        리포트 {month.count}개
        {month.unread > 0 ? ` · 새 글 ${month.unread}개` : ""}
      </p>
      {month.institutions.length > 0 ? (
        <>
          <ul className="mt-2 flex flex-wrap gap-2">
            {month.institutions.map((institution) => (
              <li key={institution.id}>
                <InstitutionChip institution={institution} withName />
              </li>
            ))}
          </ul>
          <p className="mt-3 text-[13px] leading-5 text-muted">이번 달 함께 기록을 살펴본 기관이에요.</p>
        </>
      ) : null}
    </div>
  );
}

function ReportCard({ insight }: { insight: GuardianInsight }) {
  return (
    <div className="rounded-2xl border border-line px-4 py-4">
      <div className="mb-2 flex items-center gap-2.5">
        <InstitutionIcon type={insight.from?.type} />
        <span className="min-w-0 truncate font-bold">{insight.from?.name ?? "기관 정보 없음"}</span>
        {insight.readAt ? null : (
          <span className="ml-auto shrink-0 rounded-full bg-accentsoft px-2.5 py-1 text-[12px] font-bold text-accentink">
            새 글
          </span>
        )}
      </div>
      <p className="mb-3 line-clamp-2 text-[15px] leading-6">{insight.content}</p>
      <div className="flex justify-end">
        <Link to={`/parent/report/${insight.id}`} className="text-[14px] font-semibold text-accentink">
          자세히 보기 ›
        </Link>
      </div>
    </div>
  );
}

function QuietCard({ insight }: { insight: GuardianInsight }) {
  const nothingNew = insight.quietReason === "NOTHING_NEW";
  return (
    <div className="rounded-2xl bg-surface2 px-4 py-4">
      <p className="text-[15px] font-bold">
        {nothingNew ? "이 주는 따로 알릴 내용이 없었어요" : "이 주는 특이사항이 없었어요"}
      </p>
      <p className="mt-1 text-[14px] leading-6 text-muted">
        {nothingNew
          ? "기관들이 기록을 함께 살펴봤지만 되풀이되는 모습은 없었어요."
          : "기관 기록에 따로 알릴 관찰이 없었어요."}
      </p>
    </div>
  );
}
