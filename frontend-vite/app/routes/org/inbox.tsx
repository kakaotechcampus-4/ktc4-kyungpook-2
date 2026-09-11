import { useLoaderData } from "react-router";
import { Card, EmptyState, InstitutionChip, Note, PageHeader } from "@/components/ui";
import { getInbox } from "@/lib/api";

export async function clientLoader() {
  return { items: await getInbox() };
}

export default function InboxPage() {
  const { items } = useLoaderData<typeof clientLoader>();
  const unread = items.filter((i) => !i.read);

  return (
    <>
      <PageHeader
        title="메시지"
        description="다른 기관이 보낸 검증된 지원 방법입니다 (원본이 아닌 변환된 최소 정보)"
      />

      {items.length === 0 ? (
        <EmptyState
          title="아직 받은 지원 방법이 없습니다"
          description="다른 기관이 승인된 관찰·분석 결과를 보내면 여기에 표시됩니다."
        />
      ) : (
        <>
          {unread.length > 0 ? (
            <p className="mb-4 rounded border border-accent/40 bg-accentsoft px-3 py-2.5 text-[15px] font-medium text-accentink">
              새로 도착한 지원 방법이 {unread.length}건 있습니다
            </p>
          ) : null}

          <div className="flex flex-col gap-3">
            {items.map((item) => (
              <Card key={item.id} className={item.read ? "opacity-80" : "border-accent/40"}>
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <InstitutionChip institution={item.from} withName />
                  <span className="text-[14px] text-muted">{item.childName} 관련</span>
                  <span className="ml-auto text-[13px] text-muted">{item.receivedAt}</span>
                </div>
                <p className="text-[16px] leading-7">{item.content}</p>
              </Card>
            ))}
          </div>

          <div className="mt-5">
            <Note>
              보낸 기관의 원본 기록은 전달되지 않습니다. 받는 기관에 필요한 최소 정보만 변환되어
              표시됩니다.
            </Note>
          </div>
        </>
      )}
    </>
  );
}
