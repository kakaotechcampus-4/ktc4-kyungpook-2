import Link from "next/link";
import {
  Card,
  EmptyState,
  InstitutionChip,
  PageHeader,
  QueueCard,
  ValidationBadge,
} from "@/components/ui";
import { getBlockedQueue, getGate1Queue, getInsights, getMatchingQueue } from "@/lib/api";
import { MY_INSTITUTION } from "@/lib/mock/data";

/** 파이프라인 단계 — 업로드 후 Gate 1 까지는 사람 개입 없이 자동 진행된다. */
const STAGES = ["업로드", "매칭", "검증", "요약", "Gate 1 대기"] as const;

export default async function DashboardPage() {
  const [matching, blocked, gate1, insights] = await Promise.all([
    getMatchingQueue(),
    getBlockedQueue(),
    getGate1Queue(),
    getInsights(),
  ]);
  const gate1Pending = gate1.filter((s) => s.gate1Status === "pending");
  const gate2Pending = insights.filter((i) => i.gate2Status === "pending");
  const isEmpty =
    matching.length === 0 &&
    blocked.length === 0 &&
    gate1Pending.length === 0 &&
    gate2Pending.length === 0;

  return (
    <>
      <PageHeader
        title="처리 현황"
        description="오늘 처리해야 할 일과 업로드된 기록의 진행 상황입니다"
        right={<InstitutionChip institution={MY_INSTITUTION} withName />}
      />

      {isEmpty ? (
        <EmptyState
          icon="✓"
          title="지금 처리할 일이 없습니다"
          description="기록을 업로드하면 매칭 · 검증 · 요약이 자동으로 진행되고, 승인이 필요할 때 여기에 표시됩니다."
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <QueueCard
            title="확인 필요 큐"
            count={matching.length}
            href="/queue/matching"
            description="AI가 아이를 확정하지 못함"
          />
          <QueueCard
            title="재입력 요청 큐"
            count={blocked.length}
            href="/queue/reinput"
            tone="block"
            description="BLOCK 판정 — 원본 수정 필요"
          />
          <QueueCard
            title="Gate 1 승인 대기"
            count={gate1Pending.length}
            href="/gate1"
            tone="human"
            description="요약의 사실 정확성 검토"
          />
          <QueueCard
            title="Gate 2 발송 검토"
            count={gate2Pending.length}
            href="/gate2"
            tone="human"
            description="되돌릴 수 없는 지점"
          />
        </div>
      )}

      <Card className="mt-7">
        <h2 className="mb-1 text-[17px] font-bold">처리 중인 기록</h2>
        <p className="mb-4 text-[14px] text-muted">
          업로드부터 Gate 1 대기까지는 사람 개입 없이 자동으로 진행됩니다.
        </p>

        <ol className="mb-5 flex flex-wrap items-center gap-2">
          {STAGES.map((s, i) => (
            <li key={s} className="flex items-center gap-2">
              <span
                className={`rounded px-2.5 py-1 text-[14px] font-medium ${
                  i < 3
                    ? "bg-accentsoft text-accentink"
                    : i === 3
                      ? "bg-surface2 text-ink2"
                      : "bg-humansoft text-human"
                }`}
              >
                {s}
              </span>
              {i < STAGES.length - 1 ? (
                <span aria-hidden className="text-muted">
                  ›
                </span>
              ) : null}
            </li>
          ))}
        </ol>

        <ul className="flex flex-col divide-y divide-line border-y border-line">
          <li className="flex flex-wrap items-center justify-between gap-2 py-3">
            <span className="text-[15px]">0821_관찰일지.docx</span>
            <span className="flex items-center gap-2 text-[14px] text-muted">
              <span className="inline-block h-1.5 w-40 overflow-hidden rounded bg-surface2">
                <span className="block h-full w-3/5 bg-accent" />
              </span>
              검증 중 (3/5)
            </span>
          </li>
          <li className="flex flex-wrap items-center justify-between gap-2 py-3">
            <span className="text-[15px]">0821_활동일지.docx</span>
            <span className="flex items-center gap-2 text-[14px]">
              <ValidationBadge status="REVIEW" />
              <span className="text-muted">Gate 1 대기</span>
            </span>
          </li>
          <li className="flex flex-wrap items-center justify-between gap-2 py-3">
            <span className="text-[15px]">0821_특이사항.txt</span>
            <span className="flex items-center gap-2 text-[14px]">
              <ValidationBadge status="BLOCK" />
              <Link href="/queue/reinput" className="text-accentink underline">
                재입력 요청
              </Link>
            </span>
          </li>
        </ul>
      </Card>
    </>
  );
}
