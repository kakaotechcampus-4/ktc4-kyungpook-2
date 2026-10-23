import { EmptyState, PageHeader } from "@/components/ui";

/**
 * 실연동 빌드에서 백엔드에 아직 없는 화면. mock 데이터를 실데이터처럼 띄우지 않고 이걸 보인다
 * (멘토 피드백: mock 메뉴를 실제 API 로 바꾸거나, 아직이면 그렇다고 알리기).
 * 전체 mock 모드에서는 원래 화면이 그대로 보인다 — 각 라우트가 isReady() 로 가른다.
 */
export function NotReadyPage({ title, description }: { title: string; description?: string }) {
  return (
    <>
      <PageHeader title={title} description={description} />
      <NotReadyState />
    </>
  );
}

/** 화면 일부만 준비 중일 때 그 자리에 둔다 */
export function NotReadyState() {
  return (
    <EmptyState
      icon="◦"
      title="준비 중인 기능입니다"
      description="서버 연결을 준비하고 있습니다. 연결되면 이 화면에서 바로 쓸 수 있습니다."
    />
  );
}
