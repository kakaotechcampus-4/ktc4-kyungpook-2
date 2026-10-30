import { Link, useLoaderData } from "react-router";
import { InstitutionCodeCard } from "@/components/org/InstitutionCodeCard";
import { LinkRequestCard } from "@/components/org/LinkRequestCard";
import { RosterConsentBadge } from "@/components/org/RosterConsentBadge";
import { Card, PageHeader } from "@/components/ui";
import { getChildren, getLinkRequests, getMyInstitution, loadIfReady } from "@/lib/api";

export async function clientLoader() {
  const [children, institution, linkRequests] = await Promise.all([
    getChildren(),
    loadIfReady("institutionInfo", getMyInstitution),
    loadIfReady("linkRequests", getLinkRequests),
  ]);
  return { children, institution, linkRequests: linkRequests ?? [] };
}

export default function ChildrenPage() {
  const { children, institution, linkRequests } = useLoaderData<typeof clientLoader>();

  return (
    <>
      <PageHeader
        title="아동 목록"
        description="우리 기관 명부의 아이와, 우리 기관에 대한 보호자 동의 상태를 확인합니다"
        right={
          <Link
            to="/children/new"
            className="tap inline-flex items-center rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink"
          >
            아이 등록
          </Link>
        }
      />

      {institution ? <InstitutionCodeCard institution={institution} /> : null}

      {linkRequests.length > 0 ? (
        <section aria-labelledby="link-requests-title" className="mb-6">
          <h2 id="link-requests-title" className="mb-1 text-[17px] font-bold">
            연결 요청 {linkRequests.length}건
          </h2>
          <p className="mb-3 text-[14px] leading-6 text-ink2">
            보호자가 우리 기관 코드로 연결을 요청했습니다. 관리번호를 넣어 수락하면 명부에 오르고, 보호자가
            우리 기관에 동의하면 기록을 받을 수 있습니다.
          </p>
          <div className="flex flex-col gap-3">
            {linkRequests.map((r) => (
              <LinkRequestCard key={r.child.id} request={r} />
            ))}
          </div>
        </section>
      ) : null}

      <div className="flex flex-col gap-4">
        {children.map((c) => {
          return (
            <Card key={c.id}>
              <div className="flex flex-wrap items-center gap-2.5">
                <span
                  aria-hidden
                  className="flex size-9 items-center justify-center rounded-full bg-surface2 text-[15px] font-semibold text-ink2"
                >
                  {c.name.slice(0, 1)}
                </span>
                <Link
                  to={`/children/${c.id}`}
                  className="text-[17px] font-bold hover:text-accentink hover:underline"
                >
                  {c.name}
                </Link>
                {c.externalId ? (
                  <span className="text-[14px] text-muted tabular-nums">#{c.externalId}</span>
                ) : null}
                <span className="text-[14px] text-muted tabular-nums">{c.birthDate}</span>
                <RosterConsentBadge consent={c.consent} className="ml-auto" />
              </div>

            </Card>
          );
        })}
      </div>
    </>
  );
}
