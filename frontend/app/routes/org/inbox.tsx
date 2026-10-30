import { Link, useLoaderData } from "react-router";
import { NotReadyPage } from "@/components/org/NotReady";
import { Card, EmptyState, InstitutionChip, Note, PageHeader } from "@/components/ui";
import { getInbox, isReady } from "@/lib/api";
import { formatDateTime } from "@/lib/datetime";

export async function clientLoader() {
  return { items: await getInbox() };
}

/** 백엔드에 아직 없으면 mock 데이터 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function InboxRoute() {
  if (!isReady("inbox")) {
    return (
      <NotReadyPage
        title="메시지"
        description="다른 기관이 승인해 우리 기관에 열린 지원 방법입니다"
      />
    );
  }
  return <InboxPage />;
}

/**
 * 메시지(수신함) 목록. 본문은 없다 — 카드를 눌러 열어야 보이고, 그때 우리 기관의 처음 열람 시각이
 * 남는다. 목록이 본문을 보이면 열지 않고도 다 읽어서 "언제 봤나" 가 의미를 잃는다.
 */
function InboxPage() {
  const { items } = useLoaderData<typeof clientLoader>();
  const unread = items.filter((i) => !i.readAt);

  return (
    <>
      <PageHeader
        title="메시지"
        description="다른 기관이 승인해 우리 기관에 열린 지원 방법입니다"
      />

      {items.length === 0 ? (
        <EmptyState
          title="아직 받은 지원 방법이 없습니다"
          description="다른 기관이 우리 기관도 다니는 아이의 관찰·분석 결과를 승인하면 여기에 표시됩니다."
        />
      ) : (
        <>
          {unread.length > 0 ? (
            <p className="mb-4 rounded border border-accent/40 bg-accentsoft px-3 py-2.5 text-[15px] font-medium text-accentink">
              새로 도착한 지원 방법이 {unread.length}건 있습니다
            </p>
          ) : null}

          <ul className="flex flex-col gap-3">
            {items.map((item) => (
              <li key={item.id}>
                <Link to={`/inbox/${encodeURIComponent(item.id)}`} className="block">
                  <Card
                    className={`tap hover:bg-surface2 ${item.readAt ? "opacity-80" : "border-accent/40"}`}
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      {item.readAt ? null : (
                        <span className="rounded bg-accent px-1.5 py-0.5 text-[12px] font-bold text-white">
                          새 글
                        </span>
                      )}
                      <InstitutionChip institution={item.from} withName />
                      <span className="ml-auto text-[13px] text-muted tabular-nums">
                        {formatDateTime(item.receivedAt)}
                      </span>
                    </div>
                    <p className="mt-2 text-[16px] font-semibold">
                      {item.childName}
                      <span className="ml-1.5 text-[14px] font-normal text-muted tabular-nums">
                        #{item.externalId}
                      </span>
                    </p>
                    <p className="mt-0.5 text-[14px] text-muted">{item.period} 관찰·분석 결과</p>
                  </Card>
                </Link>
              </li>
            ))}
          </ul>

          <div className="mt-5">
            <Note>
              보낸 기관의 원본 기록은 전달되지 않습니다. 승인을 거친 관찰·분석 결과만 보이고, 보호자가
              우리 기관 공유를 끄면 바로 사라집니다.
            </Note>
          </div>
        </>
      )}
    </>
  );
}
