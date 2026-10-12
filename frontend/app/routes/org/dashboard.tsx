import { useLoaderData, useRevalidator } from "react-router";
import { FileProgressList, useProgressPolling } from "@/components/org/FileProgressList";
import { Card, EmptyState, InstitutionChip, PageHeader, QueueCard } from "@/components/ui";
import {
  getBlockedQueue,
  getFileProgress,
  getGate1Queue,
  getInsights,
  getMatchingQueue,
  loadIfReady,
  retryFailedEntries,
} from "@/lib/api";
import { MY_INSTITUTION } from "@/lib/mock/data";

/** 백엔드에 아직 없는 기능은 null 로 와서 "준비 중"으로 그린다 (loadIfReady 참고) */
export async function clientLoader() {
  const [matching, blocked, gate1, insights, files] = await Promise.all([
    loadIfReady("matchingQueue", getMatchingQueue),
    loadIfReady("blockedQueue", getBlockedQueue),
    loadIfReady("gate1", getGate1Queue),
    loadIfReady("insights", getInsights),
    loadIfReady("fileProgress", getFileProgress),
  ]);
  return { matching, blocked, gate1, insights, files };
}

export default function DashboardPage() {
  const { matching, blocked, gate1, insights, files } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();
  useProgressPolling(files ?? []);
  const counts = [
    matching?.length ?? null,
    blocked?.length ?? null,
    gate1?.filter((s) => s.gate1Status === "pending").length ?? null,
    insights?.filter((i) => i.gate2Status === "pending").length ?? null,
  ] as const;
  const [matchingCount, blockedCount, gate1Count, gate2Count] = counts;
  // 준비 중인 칸은 0건으로 치지 않는다 — 모르는 걸 "할 일 없음"이라고 말하면 안 된다
  const isEmpty = counts.every((c) => c === 0);

  return (
    <>
      <PageHeader
        title="오늘의 업무"
        description="오늘 처리해야 할 일과 등록된 기록의 진행 상황입니다"
        right={<InstitutionChip institution={MY_INSTITUTION} withName />}
      />

      {isEmpty ? (
        <EmptyState
          icon="✓"
          title="지금 처리할 일이 없습니다"
          description="기록을 등록하면 매칭 · 검증 · 요약이 자동으로 진행되고, 승인이 필요할 때 여기에 표시됩니다."
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
        <h2 className="mb-1 text-[17px] font-bold">처리 중인 기록</h2>
        <p className="mb-4 text-[14px] text-muted">
          올린 파일마다 어디까지 처리됐는지 보여줍니다. 사람 확인이 필요한 건에서만 멈춥니다.
        </p>

        {files === null ? (
          <p className="text-[14px] text-muted">준비 중입니다.</p>
        ) : files.length === 0 ? (
          <p className="text-[14px] text-muted">아직 올린 파일이 없습니다.</p>
        ) : (
          <FileProgressList
            files={files}
            onRetry={async (id) => {
              await retryFailedEntries(id);
              revalidator.revalidate();
            }}
          />
        )}
      </Card>
    </>
  );
}
