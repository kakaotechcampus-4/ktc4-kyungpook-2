import { Link, useLoaderData } from "react-router";
import { NotReadyPage } from "@/components/org/NotReady";
import { Card, EmptyState, EvidenceChip, InstitutionChip, PageHeader } from "@/components/ui";
import { getInsights, isReady } from "@/lib/api";
import type { Gate2Status } from "@/lib/types";

const GATE2_LABEL: Record<Gate2Status, { text: string; cls: string }> = {
  pending: { text: "발송 검토 대기", cls: "text-human bg-humansoft" },
  approved: { text: "발송 승인됨", cls: "text-pass bg-passsoft" },
  hold: { text: "보류", cls: "text-ink2 bg-surface2" },
};

/** BE 는 상태 하나씩만 준다. 셋을 함께 물어 대기 중인 것부터 보인다. */
export async function clientLoader() {
  const [pending, approved, hold] = await Promise.all([
    getInsights("pending"),
    getInsights("approved"),
    getInsights("hold"),
  ]);
  return { insights: [...pending, ...approved, ...hold] };
}

/** 백엔드에 아직 없으면 mock 데이터 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function InsightsRoute() {
  if (!isReady("insights")) {
    return (
      <NotReadyPage
        title="관찰·분석 결과"
        description="아이의 승인된 기록에서 도출된 패턴입니다"
      />
    );
  }
  return <InsightsPage />;
}

function InsightsPage() {
  const { insights } = useLoaderData<typeof clientLoader>();

  return (
    <>
      <PageHeader
        title="관찰·분석 결과"
        description="아이의 승인된 기록에서 도출된 패턴입니다"
      />

      {insights.length === 0 ? (
        <EmptyState
          icon="◦"
          title="아직 도출된 관찰·분석 결과가 없습니다"
          description="승인된 요약이 일정 기간 쌓이면 반복 패턴과 효과적이었던 지원 방법을 찾아냅니다."
        />
      ) : (
        <div className="flex flex-col gap-4">
          {insights.map((i) => {
            const g = GATE2_LABEL[i.gate2Status];
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

                {/* 근거 칩은 누르지 않는다 — Gate 2 와 같다. 기관이 근거 기록 내용을 볼 API 가 없다 */}
                <ul className="mb-4 flex flex-wrap gap-2">
                  {i.evidence.map((e) => (
                    <li key={e.childContextId}>
                      <EvidenceChip date={e.date} label={e.label} institution={e.institution} />
                    </li>
                  ))}
                </ul>

                {/* BE 가 우리 기관이 승인 주체인 것만 주므로 대기 중이면 우리가 검토할 것이다 */}
                {i.gate2Status === "pending" ? (
                  <Link
                    to="/gate2"
                    className="tap inline-flex items-center rounded bg-human px-4 text-[15px] font-semibold text-white hover:brightness-95"
                  >
                    공유 검토하기
                  </Link>
                ) : null}
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
