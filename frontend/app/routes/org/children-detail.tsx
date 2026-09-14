import { Link, useLoaderData } from "react-router";
import {
  Card,
  ConsentStatus,
  EmptyState,
  InstitutionChip,
  Note,
  PageHeader,
  ValidationBadge,
} from "@/components/ui";
import { getChild, getTimeline } from "@/lib/api";

export async function clientLoader({ params }: { params: { id: string } }) {
  const [child, timeline] = await Promise.all([
    getChild(params.id),
    getTimeline(params.id),
  ]);
  if (!child) throw new Response("Not Found", { status: 404 });
  return { child, timeline };
}

export default function ChildDetailPage() {
  const { child, timeline } = useLoaderData<typeof clientLoader>();
  const withEntry = timeline.filter((t) => t.entry);

  return (
    <>
      <PageHeader
        title={`아동 목록 · ${child.name}`}
        description="승인된 요약이 날짜순으로 쌓인 관찰 기록입니다"
        right={
          <Link
            to="/chat"
            className="tap inline-flex items-center rounded border border-line2 px-4 text-[15px] font-semibold text-ink2 hover:bg-surface2"
          >
            상담 도우미로 이동
          </Link>
        }
      />

      <Card className="mb-6">
        <div className="mb-3 flex flex-wrap items-center gap-2.5">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded-full bg-surface2 text-[16px] font-semibold text-ink2"
          >
            {child.name.slice(0, 1)}
          </span>
          <span className="text-[18px] font-bold">{child.name}</span>
          <span className="text-[14px] text-muted tabular-nums">{child.birthDate} 생</span>
        </div>
        <p className="mb-2 text-[13px] font-semibold tracking-wider text-muted uppercase">
          연결된 기관
        </p>
        <ul className="flex flex-wrap gap-x-5 gap-y-2">
          {child.institutions.map(({ institution, consent }) => (
            <li key={institution.id} className="flex items-center gap-2 text-[15px]">
              <InstitutionChip institution={institution} withName />
              <ConsentStatus state={consent} />
            </li>
          ))}
        </ul>
      </Card>

      <h2 className="mb-3 text-[17px] font-bold">기록 타임라인 (승인된 요약)</h2>

      {withEntry.length === 0 ? (
        <>
          <EmptyState
            icon="◦"
            title="아직 승인된 요약이 없습니다"
            description="기록을 등록하고 1차 검토에서 승인하면 여기에 날짜순으로 쌓입니다. 승인되지 않은 기록은 표시되지 않습니다."
          />
          <div className="mt-4">
            <Note>
              {child.status === "pending_consent"
                ? "보호자 동의가 완료되기 전에는 기록을 올릴 수 없습니다."
                : "기록 등록 화면에서 첫 기록을 올려보세요."}
            </Note>
          </div>
        </>
      ) : (
        <ul className="flex flex-col divide-y divide-line border-y border-line">
          {timeline.map((t) => (
            <li key={t.date} className="flex gap-5 py-4">
              <span className="w-14 shrink-0 text-[15px] font-semibold text-ink2 tabular-nums">
                {t.date.slice(5).replace("-", ".")}
              </span>
              {t.entry ? (
                <div className="min-w-0 flex-1">
                  <div className="mb-1.5 flex flex-wrap items-center gap-2">
                    <span className="text-[14px] font-semibold text-ink2">
                      {t.entry.recordType} 요약
                    </span>
                    <ValidationBadge status={t.entry.validation} />
                    {t.entry.edited ? (
                      <span className="rounded bg-humansoft px-1.5 text-[12px] font-semibold text-human">
                        선생님 수정
                      </span>
                    ) : null}
                  </div>
                  <p className="mb-1.5 text-[16px] leading-7">{t.entry.content}</p>
                  <p className="text-[13px] text-muted tabular-nums">
                    원본 {t.entry.sourceCount}건 · v{t.entry.version}
                  </p>
                </div>
              ) : (
                // 기록이 없는 날은 그대로 비워 표시한다 — 추정치로 채우지 않는다
                <span className="text-[15px] text-muted">기록 없음</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
