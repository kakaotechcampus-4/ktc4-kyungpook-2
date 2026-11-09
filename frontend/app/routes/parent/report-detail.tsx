import { ChevronRight } from "lucide-react";
import { Link, useLoaderData } from "react-router";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getGuardianInsight, isReady } from "@/lib/api";
import { formatDateMonthDay, formatMonthDay } from "@/lib/datetime";
import { writeSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader({ params }: { params: { id: string } }) {
  if (!isReady("guardianInsights")) return null;
  const insight = await getGuardianInsight(params.id);
  if (!insight) throw new Response("Not Found", { status: 404 });
  return { insight };
}

export default function ReportDetailPageRoute() {
  if (!isReady("guardianInsights")) return <ParentNotReadyPage title="주간 리포트" back />;
  return <ReportDetailPage />;
}

function ReportDetailPage() {
  const { insight } = useLoaderData<typeof clientLoader>()!;
  const from = insight.from?.name ?? "기관 정보 없음";
  const approved = formatMonthDay(insight.approvedAt);
  const lookedUp = insight.evidence.some((e) => e.lookedUp);

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
            {insight.from ? `${approved} ${insight.from.name}에서 정리했어요` : `${approved} 정리됨`}
          </span>
        </span>
      </div>

      <section>
        <p className="mb-2 text-[13px] font-bold text-accentink">기관들이 함께 본 것</p>
        <p className="text-[16px] leading-7 whitespace-pre-line">{insight.content}</p>
      </section>

      {insight.evidence.length > 0 ? (
        <section>
          <p className="mb-2 text-[13px] font-bold text-accentink">이 글의 근거</p>
          {lookedUp ? (
            <p className="mb-2 text-[14px] leading-6 text-ink2">이번 기록과 비슷한 일이 있었던 지난 기록도 함께 살펴봤어요.</p>
          ) : null}
          <ul className="flex flex-col gap-2">
            {insight.evidence.map((e) => (
              <li key={e.journalEntryId}>
                <Link
                  to={`/parent/journal/${e.journalEntryId}`}
                  className="flex items-start gap-3 rounded-2xl border border-line px-4 py-3 hover:bg-surface2"
                >
                  <InstitutionIcon type={e.institution.type} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-bold">{e.institution.name}</span>
                    <span className="block text-[13px] text-muted">
                      {formatDateMonthDay(e.date)} 일지
                      {e.lookedUp ? <b className="font-semibold text-accentink"> · 지난 기록</b> : null}
                    </span>
                    {e.sentences.length > 0 ? (
                      <span className="mt-2 flex flex-col gap-1.5">
                        {e.sentences.map((sentence, index) => (
                          <span key={index} className="flex gap-1.5 text-[14px] leading-6 text-ink2">
                            <span
                              className={`mt-0.5 h-fit shrink-0 rounded-full px-2 py-0.5 text-[12px] font-bold ${
                                sentence.role === "대처"
                                  ? "bg-accentsoft text-accentink"
                                  : "bg-surface2 text-ink2"
                              }`}
                            >
                              {sentence.role}
                            </span>
                            <span className="min-w-0">{sentence.text}</span>
                          </span>
                        ))}
                      </span>
                    ) : null}
                  </span>
                  <ChevronRight aria-hidden className="mt-1 size-4 shrink-0 text-muted" />
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

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
