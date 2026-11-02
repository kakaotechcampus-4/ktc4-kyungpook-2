import { Link, useLoaderData, useSearchParams, type ShouldRevalidateFunctionArgs } from "react-router";
import { NotReadyPage } from "@/components/org/NotReady";
import { Card, EmptyState, InstitutionChip, Note, PageHeader } from "@/components/ui";
import { getInbox, isReady } from "@/lib/api";
import { formatDateTime } from "@/lib/datetime";
import { INBOX_CHILD_PARAM, inboxChildOptions, onlyInboxFilterChanged } from "@/lib/inbox";

export async function clientLoader() {
  return { items: await getInbox() };
}

/** 아이 필터만 바꾸면 다시 부르지 않는다 — 받은 목록을 화면에서 거른다 */
export function shouldRevalidate({ currentUrl, nextUrl, defaultShouldRevalidate }: ShouldRevalidateFunctionArgs) {
  if (onlyInboxFilterChanged(currentUrl, nextUrl)) return false;
  return defaultShouldRevalidate;
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
 *
 * 아이별로 거를 수 있다. 서버는 우리 기관에 열린 글을 전부 주고 화면이 아이 id 로 거른다 — 아이 한 명당
 * 주마다 몇 건이라 가볍다. 페이지 나누기가 생기면 서버 조건으로 옮긴다.
 */
function InboxPage() {
  const { items } = useLoaderData<typeof clientLoader>();
  const [searchParams, setSearchParams] = useSearchParams();
  const childId = searchParams.get(INBOX_CHILD_PARAM);
  const options = inboxChildOptions(items);
  const shown = childId ? items.filter((i) => i.childId === childId) : items;
  const unread = shown.filter((i) => !i.readAt);
  // 사라진 글(동의 철회 · 명부 제외)에서 아이 이름을 다시 꺼내지 않는다 — 이름 없이 "선택한 아이" 로만
  const missing = childId != null && !options.some((o) => o.childId === childId);

  function choose(next: string) {
    setSearchParams(next ? { [INBOX_CHILD_PARAM]: next } : {}, { replace: true });
  }

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
          {options.length > 1 || childId ? (
            <label className="mb-4 flex max-w-sm flex-col gap-1 text-[13px] text-ink2">
              아이
              <select
                value={childId ?? ""}
                onChange={(e) => choose(e.target.value)}
                className="min-h-10 rounded border border-line2 bg-surface px-2.5 text-[15px] text-ink"
              >
                <option value="">전체 아이</option>
                {missing ? <option value={childId}>선택한 아이 (받은 글 없음)</option> : null}
                {options.map((o) => (
                  <option key={o.childId} value={o.childId}>
                    {o.label}
                    {o.unread > 0 ? ` · 새 글 ${o.unread}` : ""}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {unread.length > 0 ? (
            <p className="mb-4 rounded border border-accent/40 bg-accentsoft px-3 py-2.5 text-[15px] font-medium text-accentink">
              새로 도착한 지원 방법이 {unread.length}건 있습니다
            </p>
          ) : null}

          {shown.length === 0 ? (
            <div className="flex flex-col items-center gap-3">
              <div className="w-full">
                <EmptyState title="이 아이에 대해 받은 글이 없습니다" />
              </div>
              <Link
                to="/inbox"
                replace
                className="tap inline-flex items-center rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
              >
                전체 보기
              </Link>
            </div>
          ) : null}

          <ul className="flex flex-col gap-3">
            {shown.map((item) => (
              <li key={item.id}>
                <Link
                  to={`/inbox/${encodeURIComponent(item.id)}${childId ? `?${new URLSearchParams({ [INBOX_CHILD_PARAM]: childId })}` : ""}`}
                  className="block"
                >
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
                      {item.externalId ? (
                        <span className="ml-1.5 text-[14px] font-normal text-muted tabular-nums">
                          #{item.externalId}
                        </span>
                      ) : null}
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
