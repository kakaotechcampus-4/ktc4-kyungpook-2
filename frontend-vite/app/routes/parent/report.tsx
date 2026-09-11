import { useState } from "react";
import { Link, useLoaderData } from "react-router";
import { WifiOff } from "lucide-react";
import { EmptyState } from "@/components/ui";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getCareReport, getParentChildren, getPendingInstitutionRequests } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader() {
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [weekly, monthly, pending] = await Promise.all([
    getCareReport(childId, "weekly"),
    getCareReport(childId, "monthly"),
    getPendingInstitutionRequests(childId),
  ]);
  return { weekly, monthly, pending };
}

export default function ReportPage() {
  const { weekly, monthly, pending } = useLoaderData<typeof clientLoader>();
  const [period, setPeriod] = useState<"weekly" | "monthly">("weekly");
  const [broken, setBroken] = useState(false);
  const report = period === "weekly" ? weekly : monthly;
  const max = report ? Math.max(...report.trend.map((t) => t.value)) : 0;

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="케어 리포트"
        subtitle={period === "weekly" ? "주간 리포트" : "월간 리포트"}
        showChildSwitch
        alert={pending.length > 0}
      />

      <div className="flex rounded-2xl bg-surface2 p-1">
        {(["weekly", "monthly"] as const).map((p) => (
          <button
            key={p}
            onClick={() => setPeriod(p)}
            className={`tap flex-1 rounded-xl text-[15px] font-bold ${
              period === p ? "bg-surface text-accentink shadow-sm" : "text-muted"
            }`}
          >
            {p === "weekly" ? "주간" : "월간"}
          </button>
        ))}
      </div>

      {!report ? (
        <EmptyState
          icon="◦"
          title="아직 리포트가 없습니다"
          description="기록이 쌓이면 변화 추이와 패턴을 정리해서 보여드립니다."
        />
      ) : broken ? (
        <div className="flex flex-col items-center gap-3 py-16 text-center">
          <WifiOff size={40} strokeWidth={1.5} className="text-block" />
          <p className="text-[17px] font-bold">연결이 잠시 끊겼습니다</p>
          <p className="text-[14px] text-muted">기록은 그대로 있습니다. 잠시 후 다시 시도해주세요.</p>
          <button
            onClick={() => setBroken(false)}
            className="tap h-12 rounded-2xl bg-accent px-6 text-[15px] font-bold text-white"
          >
            다시 시도
          </button>
        </div>
      ) : (
        <>
          <div className="rounded-2xl bg-accentsoft px-4 py-4">
            <p className="mb-1.5 text-[15px] leading-7 tabular-nums text-accentink">
              {report.rangeLabel}
            </p>
            <p className="text-[15px] leading-7 text-ink">{report.summary}</p>
          </div>

          <section className="rounded-2xl border border-line px-4 py-4">
            <h2 className="mb-4 text-[16px] font-bold">{report.trendTitle}</h2>
            <div className="mb-2 flex items-end gap-3">
              {report.trend.map((t, i) => (
                <div key={t.label} className="flex flex-1 flex-col items-center gap-1.5">
                  <span className="text-[13px] font-bold tabular-nums text-ink2">{t.value}</span>
                  <div
                    className={`w-full rounded-t-lg ${
                      i >= report.trend.length - 2 ? "bg-accent" : "bg-accentsoft"
                    }`}
                    style={{ height: `${8 + (t.value / max) * 110}px` }}
                  />
                  <span className="text-[12px] text-muted">{t.label}</span>
                </div>
              ))}
            </div>
            <p className="mt-3 text-[15px] leading-6 text-ink2">{report.trendInsight}</p>
            <Link
              to={`/parent/report/evidence?ids=${report.trendEvidenceIds.join(",")}`}
              className="mt-2 inline-block text-[14px] font-semibold text-accentink"
            >
              관련 일지 보기 ›
            </Link>
          </section>

          {report.patterns.length > 0 ? (
            <section className="rounded-2xl border border-line px-4 py-4">
              <h2 className="mb-3 text-[16px] font-bold">반복되는 패턴</h2>
              {report.patterns.map((p, i) => (
                <div key={i} className="flex items-start gap-3">
                  <span
                    aria-hidden
                    className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-surface2 text-ink2"
                  >
                    ⏱
                  </span>
                  <div>
                    <p className="text-[15px] leading-6">{p.text}</p>
                    <Link
                      to={`/parent/report/evidence?ids=${p.evidenceIds.join(",")}`}
                      className="text-[14px] font-semibold text-accentink"
                    >
                      관련 일지 보기 ›
                    </Link>
                  </div>
                </div>
              ))}
            </section>
          ) : null}

          {report.tips.length > 0 ? (
            <section className="rounded-2xl border border-line px-4 py-4">
              <h2 className="mb-3 text-[16px] font-bold">집에서 해보면 좋은 것</h2>
              <ul className="flex flex-col gap-2">
                {report.tips.map((tip, i) => (
                  <li key={i} className="text-[15px] leading-6">
                    · {tip}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <p className="text-[13px] text-muted">이 내용은 아래 일지를 바탕으로 정리했습니다.</p>

          <button
            onClick={() => setBroken(true)}
            className="text-[13px] text-muted underline"
          >
            연결 끊김 상태 보기
          </button>
        </>
      )}
    </div>
  );
}
