import { Link, useLoaderData } from "react-router";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getGuardianInsight, isReady } from "@/lib/api";
import { formatMonthDay } from "@/lib/datetime";
import { writeSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader({ params }: { params: { id: string } }) {
  if (!isReady("guardianInsights")) return null;
  // 처음 열면 서버가 본 시각을 남긴다. 볼 수 없는 글은 undefined 로 온다 → "페이지를 찾을 수 없습니다"
  const insight = await getGuardianInsight(params.id);
  if (!insight) throw new Response("Not Found", { status: 404 });
  return { insight };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function ReportDetailPageRoute() {
  if (!isReady("guardianInsights")) return <ParentNotReadyPage title="주간 리포트" back />;
  return <ReportDetailPage />;
}

/**
 * 받은 리포트 한 편 (G-36). 누가 정리했고 지금 누가 함께 보는지를 글 바로 아래에서 알린다 — 글마다 기관을 고르는
 * 단계는 없고, 보호자는 기관별 공유 동의를 꺼서 막는다. 그 길이 설정이다.
 *
 * 상세를 여는 것만으로는 고른 아이를 바꾸지 않는다. 설정 링크를 누를 때 이 글의 아이를 고른다 — 다른 아이를 고른
 * 채 이 주소로 바로 들어와도 설정이 엉뚱한 아이의 동의를 보여 주지 않는다. 주소에 아이를 싣지 않는 이유는 설정에서
 * 아이를 바꿀 때 주소의 값이 다시 적용돼 되돌아가기 때문이다.
 */
function ReportDetailPage() {
  const { insight } = useLoaderData<typeof clientLoader>()!;
  const from = insight.from?.name ?? "기관 정보 없음";
  const approved = formatMonthDay(insight.approvedAt);

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="주간 리포트"
        subtitle={insight.childName ? `${insight.childName} · ${insight.period}` : insight.period}
        back
      />

      <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
        <InstitutionIcon type={insight.from?.type} />
        <span className="min-w-0">
          <span className="block truncate font-bold">{from}</span>
          <span className="block text-[13px] text-muted">
            {/* "…가/이" 는 기관 이름의 받침에 따라 달라 "…에서" 로 쓴다 */}
            {insight.from ? `${approved} ${insight.from.name}에서 정리했어요` : `${approved} 정리됨`}
          </span>
        </span>
      </div>

      <section>
        <p className="mb-2 text-[13px] font-bold text-accentink">기관들이 함께 본 것</p>
        <p className="text-[16px] leading-7 whitespace-pre-line">{insight.content}</p>
      </section>

      <section className="rounded-2xl bg-surface2 px-4 py-4">
        <h2 className="mb-2 text-[15px] font-bold">함께 보는 기관</h2>
        {insight.sharedWith.length === 0 ? (
          <p className="text-[14px] leading-6 text-ink2">이 글은 보호자님만 보고 있어요.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {insight.sharedWith.map((institution) => (
              <li key={institution.id}>
                <InstitutionChip institution={institution} withName />
              </li>
            ))}
          </ul>
        )}
        <p className="mt-3 text-[13px] leading-5 text-muted">
          기관별 공유 동의를 끄면 그 기관은 더 이상 이 글을 볼 수 없어요.
        </p>
        <Link
          to="/parent/settings"
          onClick={() => writeSelectedChildId(insight.childId)}
          className="mt-2 inline-block text-[14px] font-semibold text-accentink"
        >
          설정에서 공유 동의 보기 ›
        </Link>
      </section>
    </div>
  );
}
