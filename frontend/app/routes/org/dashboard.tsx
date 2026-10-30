import { useLoaderData } from "react-router";
import { SummaryGroupBoardView } from "@/components/org/SummaryGroupBoard";
import { Card, EmptyState, InstitutionChip, PageHeader, QueueCard } from "@/components/ui";
import {
  getBlockedQueue,
  getGate1Queue,
  getInsights,
  getMatchingQueue,
  getSummaryGroups,
  loadIfReady,
} from "@/lib/api";
import { isAuthMock } from "@/lib/auth";
import { MY_INSTITUTION } from "@/lib/mock/data";

/** 백엔드에 아직 없는 기능은 null 로 와서 "준비 중"으로 그린다 (loadIfReady 참고) */
export async function clientLoader() {
  const [matching, blocked, gate1, insights, board] = await Promise.all([
    loadIfReady("matchingQueue", getMatchingQueue),
    loadIfReady("blockedQueue", getBlockedQueue),
    loadIfReady("gate1", getGate1Queue),
    loadIfReady("insights", getInsights),
    loadIfReady("summaryGroups", getSummaryGroups),
  ]);
  return { matching, blocked, gate1, insights, board };
}

/**
 * 오늘의 업무 — 위는 사람이 처리할 큐 건수, 아래는 아이별 진행 현황(O-34 묶음 카드).
 * 파일별 진행(O-26)은 기록 등록 화면이 보여준다.
 */
export default function DashboardPage() {
  const { matching, blocked, gate1, insights, board } = useLoaderData<typeof clientLoader>();
  const counts = [
    matching?.length ?? null,
    blocked?.length ?? null,
    gate1?.filter((s) => s.gate1Status === "pending").length ?? null,
    insights?.length ?? null,
  ] as const;
  const [matchingCount, blockedCount, gate1Count, gate2Count] = counts;
  // 준비 중인 칸은 0건으로 치지 않는다 — 모르는 걸 "할 일 없음"이라고 말하면 안 된다
  const isEmpty = counts.every((c) => c === 0) && board !== null && board.groups.length === 0;

  return (
    <>
      <PageHeader
        title="오늘의 업무"
        description="오늘 처리해야 할 일과 등록된 기록의 진행 상황입니다"
        // 실연동에서는 숨긴다. 내 기관명을 받을 API 가 아직 없어, mock 기관명을 띄우면 다른 기관이 보인다
        right={isAuthMock() ? <InstitutionChip institution={MY_INSTITUTION} withName /> : null}
      />

      {isEmpty ? (
        <EmptyState
          icon="✓"
          title="지금 처리할 일이 없습니다"
          description="기록을 등록하면 매칭 · 검증이 자동으로 진행되고, 요약할 기록은 아래 아이별 진행 현황에 나타납니다."
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <QueueCard
            title="확인이 필요한 기록"
            count={matchingCount}
            href="/queue/matching"
            description="AI가 아이를 확정하지 못함"
          />
          <QueueCard
            title="수정 요청"
            count={blockedCount}
            href="/queue/reinput"
            tone="block"
            description="BLOCK 판정 — 원본 수정 필요"
          />
          <QueueCard
            title="1차 검토 대기"
            count={gate1Count}
            href="/gate1"
            tone="human"
            description="요약의 사실 정확성 검토"
          />
          <QueueCard
            title="공유할 기록"
            count={gate2Count}
            href="/gate2"
            tone="human"
            description="되돌릴 수 없는 지점"
          />
        </div>
      )}

      <Card className="mt-7">
        {board === null ? (
          <>
            <h2 className="mb-1 text-[17px] font-bold">아이별 진행 현황</h2>
            <p className="text-[14px] text-muted">준비 중입니다.</p>
          </>
        ) : (
          <SummaryGroupBoardView initial={board} />
        )}
      </Card>
    </>
  );
}
