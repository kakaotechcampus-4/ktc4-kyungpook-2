import { MatchingQueueClient } from "./client";
import { PageHeader } from "@/components/ui";
import { getMatchingQueue } from "@/lib/api";

export default async function MatchingQueuePage() {
  const items = await getMatchingQueue();
  return (
    <>
      <PageHeader
        title="확인 필요 큐 · 아이별 정리"
        description="AI가 아이를 확정하지 못한 기록을 사람이 지정합니다"
      />
      <MatchingQueueClient items={items} />
    </>
  );
}
