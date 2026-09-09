import { ChatClient } from "./client";
import { PageHeader } from "@/components/ui";
import { getChildren } from "@/lib/api";

export default async function ChatPage() {
  const children = await getChildren();
  return (
    <>
      <PageHeader
        title="상담 도우미"
        description="승인된 기록에 근거해서만 답합니다"
      />
      <ChatClient childList={children.filter((c) => c.status === "active")} />
    </>
  );
}
