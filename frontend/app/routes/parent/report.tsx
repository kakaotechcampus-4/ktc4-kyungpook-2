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
  // 서버에 아직 없으면 mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("guardianInsights")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [insights, pending, openChecklists] = await Promise.all([
    getGuardianInsights(),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
    loadOpenChecklistCount(childId),
  ]);
  // 서버는 내 모든 아이 것을 준다. 헤더에서 고른 아이 것만 보인다
  const child = kids.find((k) => k.id === childId);
  return {
    childName: child?.name ?? "",
    insights: insights.filter((i) => i.childId === childId),
    pending,
    openChecklists: openChecklists ?? 0,
  };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function ReportPageRoute() {
  if (!isReady("guardianInsights")) return <ParentNotReadyPage title="리포트" showChildSwitch />;
  return <ReportPage />;
}

type Week = { period: string; from: string; to: string; items: GuardianInsight[] };
type Month = { key: string; label: string; weeks: Week[]; count: number; unread: number; institutions: Institution[] };

/**
 * 달 → 주 → 글로 묶는다.
 *
 * **달은 `periodFrom` 으로 정한다.** `period` 글자("2026년 10월 3주차")를 잘라 쓰면 깨진다 —
 * 9/29~10/5 처럼 달에 걸친 주가 있고, BE 가 문구를 바꾸면 같이 깨진다. BE 의 `periodLabel` 도
 * `periodFrom` 으로 주를 정하므로 기준이 같다.
 *
 * 서버 순서(승인 시각 최근 순)를 그대로 쓴다 — 여기서 다시 정렬하지 않는다.
 *
 * "특이사항 없음" 주(quiet, #470)는 그 주 자리에만 놓는다. 리포트가 아니라 글 수 · 새 글 · 기관에 세지 않는다.
 */
function groupByMonth(insights: GuardianInsight[]): Month[] {
  const months: Month[] = [];
  for (const insight of insights) {
    const key = insight.periodFrom.slice(0, 7); // "2026-10"
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
    // 그 달에 함께 기록을 살펴본 기관 — 정리한 기관과 근거를 낸 기관 모두. 중복은 한 번만.
    for (const institution of [insight.from, ...insight.evidence.map((e) => e.institution)]) {
      if (institution && !month.institutions.some((i) => i.id === institution.id)) {
        month.institutions.push(institution);
      }
    }
  }
  return months;
}

/** "4주 · 10월 19일 ~ 25일". 끝 날짜는 달이 같으면 일만 쓴다 — "10월 19일 ~ 10월 25일" 은 길다 */
function weekLabel(week: Week): string {
  const order = week.period.match(/(\d+)주차/)?.[1];
  const sameMonth = week.from.slice(0, 7) === week.to.slice(0, 7);
  const to = sameMonth ? `${Number(week.to.slice(8, 10))}일` : formatDateMonthDay(week.to);
  return `${order ? `${order}주 · ` : ""}${formatDateMonthDay(week.from)} ~ ${to}`;
}

/**
 * 리포트 탭 — 담당자가 Gate 2 에서 승인해 보호자에게 열린 인사이트 (G-35). 기관들이 일주일 기록을 겹쳐 보고
 * 정리한 글이다.
 *
 * **달로 묶고 이번 달만 펼친다**. 주 단위로 쭉 나열하면 몇 달 치가 쌓였을 때 지난주를 찾기 어렵다.
 * 지난 달은 한 줄로 접고, 누르면 그 자리에서 펼친다 — 화면을 옮기지 않는다.
 *
 * 아직 열어 보지 않은 글(readAt 없음)에 "새 글"을 붙인다 — 상세를 열어야 본 시각이 남는다.
 */
function ReportPage() {
  const { childName, insights, pending, openChecklists } = useLoaderData<typeof clientLoader>()!;
  const months = groupByMonth(insights);
  // 펼친 달. 처음에는 가장 최근 달만 — 아이를 바꾸면 그 아이의 가장 최근 달로 다시 잡힌다.
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
            {/* 가장 최근 달 다음부터가 "지난 달" 이다. 한 달뿐이면 이 제목이 없다 */}
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
              /* 접힌 달. 같은 화면에서 펼친다 — 누르면 이 자리가 위 모양으로 바뀐다 */
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

/** 한 달 요약 상자. 상세의 "함께 보는 기관" 상자와 같은 모양이다 */
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

/** 리포트 한 줄. 모양은 주 단위 목록에서 쓰던 카드 그대로다 */
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

/**
 * 리포트가 없는 지난 주 (#470). 누를 수 없다 — 열어 볼 상세가 없다. 그 주에 리포트가 생기면 서버가 이 항목 대신
 * 리포트를 준다.
 */
function QuietCard({ insight }: { insight: GuardianInsight }) {
  const nothingNew = insight.quietReason === "NOTHING_NEW";
  return (
    <div className="rounded-2xl bg-surface2 px-4 py-4">
      <p className="text-[15px] font-bold">
        {nothingNew ? "이번 주는 따로 알릴 내용이 없었어요" : "이번 주는 특이사항이 없어요"}
      </p>
      <p className="mt-1 text-[14px] leading-6 text-muted">
        {nothingNew
          ? "기관들이 기록을 함께 살펴봤지만 되풀이되는 모습은 없었어요."
          : "기관 기록에 따로 알릴 관찰이 없었어요."}
      </p>
    </div>
  );
}
