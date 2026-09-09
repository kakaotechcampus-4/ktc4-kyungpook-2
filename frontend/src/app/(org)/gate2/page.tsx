import { Gate2Client } from "./client";
import { EmptyState, PageHeader } from "@/components/ui";
import { getInsights } from "@/lib/api";
import { MY_INSTITUTION } from "@/lib/mock/data";

export default async function Gate2Page() {
  const insights = await getInsights();
  // 승인 주체는 근거 요약을 최다 제공한 기관이다. 내 기관이 근거 제공자인 것만 보인다.
  const mine = insights.filter(
    (i) => i.primarySource.id === MY_INSTITUTION.id && i.gate2Status === "pending",
  );

  return (
    <>
      <PageHeader
        title="Gate 2 · 발송 검토"
        description="근거 기록을 제공한 선생님이 발송 적절성을 판단합니다"
      />
      {mine.length === 0 ? (
        <EmptyState
          icon="✓"
          title="발송을 검토할 Insight 가 없습니다"
          description="우리 기관 기록이 근거가 된 Insight 가 생기면 여기에 표시됩니다."
        />
      ) : (
        <div className="flex flex-col gap-6">
          {mine.map((i) => (
            <Gate2Client key={i.id} insight={i} />
          ))}
        </div>
      )}
    </>
  );
}
