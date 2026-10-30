import { Link, useLoaderData } from "react-router";
import { EmptyState } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import {
  getGuardianInsights,
  getParentChildren,
  getPendingInstitutionRequests,
  isReady,
  loadIfReady,
} from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { GuardianInsight } from "@/lib/types";

export async function clientLoader() {
  // 서버에 아직 없으면 mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("guardianInsights")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [insights, pending] = await Promise.all([
    getGuardianInsights(),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
  ]);
  // 서버는 내 모든 아이 것을 준다. 헤더에서 고른 아이 것만 보인다
  const child = kids.find((k) => k.id === childId);
  return { childName: child?.name ?? "", insights: insights.filter((i) => i.childId === childId), pending };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function ReportPageRoute() {
  if (!isReady("guardianInsights")) return <ParentNotReadyPage title="주간 리포트" showChildSwitch />;
  return <ReportPage />;
}

/**
 * 리포트 탭 — 담당자가 Gate 2 에서 승인해 보호자에게 열린 인사이트 (G-35). 기관들이 일주일 기록을 겹쳐 보고 정리한
 * 글이라 "주간 리포트"라고 부른다. 기간별로 묶고, 묶음 순서는 서버 순서(승인 시각 최근 순) 그대로다.
 * 아직 열어 보지 않은 글(readAt 없음)에 "새 글"을 붙인다 — 상세를 열어야 본 시각이 남는다.
 */
function ReportPage() {
  const { childName, insights, pending } = useLoaderData<typeof clientLoader>()!;
  const groups = insights.reduce<{ period: string; items: GuardianInsight[] }[]>((acc, insight) => {
    const group = acc.find((g) => g.period === insight.period);
    if (group) group.items.push(insight);
    else acc.push({ period: insight.period, items: [insight] });
    return acc;
  }, []);

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="주간 리포트"
        subtitle={childName}
        showChildSwitch
        alert={(pending ?? []).length > 0}
      />

      {groups.length === 0 ? (
        <EmptyState
          icon="◦"
          title="아직 받은 리포트가 없어요"
          description="기관들이 일주일 기록을 함께 살펴 정리하면 여기에 도착해요."
        />
      ) : (
        groups.map(({ period, items }) => (
          <section key={period}>
            <h2 className="mb-2 text-[15px] font-bold text-ink2">{period}</h2>
            <div className="flex flex-col gap-3">
              {items.map((insight) => (
                <div key={insight.id} className="rounded-2xl border border-line px-4 py-4">
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
                    <Link
                      to={`/parent/report/${insight.id}`}
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
