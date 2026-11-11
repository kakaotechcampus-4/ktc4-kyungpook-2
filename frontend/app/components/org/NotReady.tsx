import { EmptyState, PageHeader } from "@/components/ui";

export function NotReadyPage({ title }: { title: string }) {
  return (
    <>
      <PageHeader title={title} />
      <NotReadyState />
    </>
  );
}

export function NotReadyState() {
  return (
    <EmptyState
      icon="none"
      title="준비 중인 기능입니다"
      description="서버 연결을 준비하고 있습니다. 연결되면 이 화면에서 바로 쓸 수 있습니다."
    />
  );
}
