import Link from "next/link";
import {
  Card,
  EmptyState,
  EvidenceChip,
  InstitutionChip,
  PageHeader,
} from "@/components/ui";
import { getInsights } from "@/lib/api";
import { MY_INSTITUTION } from "@/lib/mock/data";
import type { Gate2Status } from "@/lib/types";

const GATE2_LABEL: Record<Gate2Status, { text: string; cls: string }> = {
  pending: { text: "발송 검토 대기", cls: "text-human bg-humansoft" },
  approved: { text: "발송 승인됨", cls: "text-pass bg-passsoft" },
  sent: { text: "발송 완료", cls: "text-pass bg-passsoft" },
  held: { text: "보류", cls: "text-ink2 bg-surface2" },
};

export default async function InsightsPage() {
  const insights = await getInsights();

  return (
    <>
      <PageHeader
        title="Insight 목록"
        description="Child Context 에 쌓인 승인 기록에서 도출된 패턴입니다"
      />

      {insights.length === 0 ? (
        <EmptyState
          icon="◦"
          title="아직 도출된 Insight 가 없습니다"
          description="승인된 요약이 일정 기간 쌓이면 반복 패턴과 효과적이었던 지원 방법을 찾아냅니다."
        />
      ) : (
        <div className="flex flex-col gap-4">
          {insights.map((i) => {
            const g = GATE2_LABEL[i.gate2Status];
            const canApprove =
              i.gate2Status === "pending" && i.primarySource.id === MY_INSTITUTION.id;
            return (
              <Card key={i.id}>
                <div className="mb-3 flex flex-wrap items-center gap-2.5">
                  <span className="text-[16px] font-bold">{i.childName}</span>
                  <span className="text-[14px] text-muted">{i.period}</span>
                  <span
                    className={`ml-auto rounded px-2 py-0.5 text-[13px] font-semibold ${g.cls}`}
                  >
                    {g.text}
                  </span>
                </div>

                <p className="mb-3 text-[16px] leading-7">{i.content}</p>

                <p className="mb-2 flex flex-wrap items-center gap-1.5 text-[14px] text-ink2">
                  <span className="font-semibold">근거를 제공한 기관</span>
                  <span className="text-muted">·</span>
                  <InstitutionChip institution={i.primarySource} withName />
                </p>

                <ul className="mb-4 flex flex-wrap gap-2">
                  {i.evidence.map((e) => (
                    <li key={e.childContextId}>
                      <EvidenceChip
                        date={e.date}
                        label={e.label}
                        institution={e.institution}
                        href={`/children/${i.childId}`}
                      />
                    </li>
                  ))}
                </ul>

                {canApprove ? (
                  <Link
                    href="/gate2"
                    className="tap inline-flex items-center rounded bg-human px-4 text-[15px] font-semibold text-white hover:brightness-95"
                  >
                    발송 검토하기
                  </Link>
                ) : i.gate2Status === "pending" ? (
                  <p className="text-[14px] text-muted">
                    이 Insight 는 근거를 제공한 {i.primarySource.name} 선생님이 검토합니다.
                  </p>
                ) : null}
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
