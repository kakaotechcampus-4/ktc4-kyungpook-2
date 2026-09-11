import { Gate1Client } from "./client";
import { PageHeader } from "@/components/ui";
import { getGate1Queue } from "@/lib/api";

export default async function Gate1Page() {
  const items = await getGate1Queue();
  return (
    <>
      <PageHeader
        title="Gate 1 · 요약 검토"
        description="업로드한 선생님이 요약의 사실 정확성을 승인합니다"
      />
      <Gate1Client items={items} />
    </>
  );
}
