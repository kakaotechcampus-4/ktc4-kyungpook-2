import { useEffect } from "react";
import { Link, useLoaderData, useRevalidator } from "react-router";
import { NotReadyPage } from "@/components/org/NotReady";
import { Card, EmptyState, InstitutionChip, Note, PageHeader } from "@/components/ui";
import { ApiError, getInboxItem, isReady } from "@/lib/api";
import { formatDateTime } from "@/lib/datetime";

/**
 * 단건을 여는 순간 서버가 우리 기관의 처음 열람 시각을 남긴다.
 * 404 는 "없음" 이 아니라 "지금 우리 기관이 볼 수 없음" 이다 — 보호자가 공유를 껐거나 명부에서 빠진
 * 아이의 글도 같은 404 로 온다. 이유를 나누지 않으므로 화면도 한 가지 안내만 한다.
 */
export async function clientLoader({ params }: { params: { id: string } }) {
  try {
    return { item: await getInboxItem(params.id) };
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return { item: null };
    throw err;
  }
}

export default function InboxDetailRoute() {
  if (!isReady("inbox")) {
    return <NotReadyPage title="메시지" description="다른 기관이 승인해 우리 기관에 열린 지원 방법입니다" />;
  }
  return <InboxDetailPage />;
}

function InboxDetailPage() {
  const { item } = useLoaderData<typeof clientLoader>();
  const revalidator = useRevalidator();

  // 레이아웃 로더는 하위 화면 이동에 다시 돌지 않는다. 방금 읽음이 찍혔으니 사이드바 "메시지" 배지를 다시 센다.
  useEffect(() => {
    if (item) revalidator.revalidate();
    // 열 때 한 번만. revalidate 가 이 로더도 다시 돌리지만 열람 시각은 처음 값 그대로라 해가 없다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const back = (
    <Link
      to="/inbox"
      className="tap inline-flex items-center rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
    >
      메시지 목록
    </Link>
  );

  if (!item) {
    return (
      <>
        <PageHeader title="메시지" right={back} />
        <EmptyState
          title="볼 수 없는 글이에요"
          description="보호자가 우리 기관 공유를 껐거나, 아이가 우리 기관 명부에서 빠졌을 수 있습니다."
        />
      </>
    );
  }

  return (
    <>
      <PageHeader title={`메시지 · ${item.childName}`} description={`${item.period} 관찰·분석 결과`} right={back} />

      <Card>
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <InstitutionChip institution={item.from} withName />
          <span className="text-[14px] text-muted">
            {item.childName}
            <span className="ml-1 tabular-nums">#{item.externalId}</span>
          </span>
          <span className="ml-auto text-[13px] text-muted tabular-nums">
            {formatDateTime(item.receivedAt)} 도착
          </span>
        </div>
        <p className="text-[17px] leading-8">{item.content}</p>
        {item.readAt ? (
          <p className="mt-3 text-[13px] text-muted tabular-nums">
            우리 기관에서 처음 연 시각 {formatDateTime(item.readAt)}
          </p>
        ) : null}
      </Card>

      <div className="mt-5">
        <Note>
          보낸 기관의 원본 기록은 전달되지 않습니다. 승인을 거친 관찰·분석 결과만 보이고, 보호자가 우리 기관
          공유를 끄면 이 글도 더 볼 수 없습니다.
        </Note>
      </div>
    </>
  );
}
