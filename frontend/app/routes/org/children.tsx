import { ArrowRight, Plus } from "lucide-react";
import { Link, useLoaderData } from "react-router";
import { InstitutionCodeCard } from "@/components/org/InstitutionCodeCard";
import { LinkRequestCard } from "@/components/org/LinkRequestCard";
import { RosterConsentBadge } from "@/components/org/RosterConsentBadge";
import { Card, PageHeader, PanelHead } from "@/components/ui";
import { getChildren, getLinkRequests, getMyInstitution, loadIfReady } from "@/lib/api";

export async function clientLoader() {
  const [children, institution, linkRequests] = await Promise.all([
    getChildren(),
    loadIfReady("institutionInfo", getMyInstitution),
    loadIfReady("linkRequests", getLinkRequests),
  ]);
  return { children, institution, linkRequests: linkRequests ?? [] };
}

/**
 * 아이들 — 캔버스 10-kids.
 *
 * 캔버스는 아이마다 "기록 12 / 내 차례 2" 와 다니는 기관 세 곳의 동의를 함께 보여 준다.
 * **그 칸은 비워 뒀다.** 명부 API(O-10)가 주는 것은 이름 · 관리번호 · 생년월일 · 우리 기관 동의뿐이고,
 * 다른 기관에 다닌다는 사실은 보호자가 우리에게 알리는 데 동의한 적이 없다(types.ts ChildListItem).
 * 숫자를 채우려면 BE 가 먼저 그 칸을 줘야 한다 — 추정치로 메우지 않는다.
 */
export default function ChildrenPage() {
  const { children, institution, linkRequests } = useLoaderData<typeof clientLoader>();

  const waiting = children.filter((c) => c.consent === "not_granted").length;

  return (
    <>
      <PageHeader
        eyebrow={
          <>
            {children.length}명
            {waiting > 0 ? ` · 보호자 동의 기다림 ${waiting}명` : null}
          </>
        }
        title="아동 목록"
        right={
          <Link
            to="/children/new"
            className="tap inline-flex items-center gap-1.5 rounded-full bg-ink px-5 text-[15px] font-bold text-paper hover:brightness-110"
          >
            <Plus aria-hidden size={16} strokeWidth={2.4} />
            아이 등록
          </Link>
        }
      />

      <div className="flex flex-col gap-5">
        {institution ? <InstitutionCodeCard institution={institution} /> : null}

        {linkRequests.length > 0 ? (
          <Card>
            <PanelHead
              title={`연결 요청 ${linkRequests.length}건`}
              note="보호자가 우리 기관 코드로 연결을 요청했습니다. 관리번호를 넣어 수락하면 명부에 오르고, 보호자가 우리 기관에 동의하면 기록을 받을 수 있습니다."
            />
            <div className="flex flex-col gap-3">
              {linkRequests.map((r) => (
                <LinkRequestCard key={r.child.id} request={r} />
              ))}
            </div>
          </Card>
        ) : null}

        {/* 캔버스처럼 아이들을 카드 한 장 안에 줄로 쌓는다 — 아이마다 카드를 주면 목록이 끊겨 보인다 */}
        <Card className="p-0">
          <ul>
            {children.map((c) => (
              <li key={c.id} className="border-t border-line px-6 py-5 first:border-t-0">
                <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                  <Link
                    to={`/children/${c.id}`}
                    className="group flex min-w-0 flex-1 items-center gap-2.5"
                  >
                    <span className="truncate text-[19px] font-extrabold group-hover:text-accentink">
                      {c.name}
                    </span>
                    <ArrowRight
                      aria-hidden
                      size={16}
                      strokeWidth={2.2}
                      className="shrink-0 text-line2 transition-transform duration-200 group-hover:translate-x-0.5 group-hover:text-accentink"
                    />
                  </Link>
                  <RosterConsentBadge consent={c.consent} />
                </div>

                <p className="mt-1 flex flex-wrap items-center gap-x-2.5 text-[14px] text-muted tabular-nums">
                  {c.externalId ? <span>#{c.externalId}</span> : null}
                  <span>{c.birthDate}</span>
                </p>

                {/* 보호자가 아직 동의 전이면 코드를 잊었거나 10분이 지났을 수 있다 — 다시 보낼 곳으로 바로 잇는다 */}
                {c.consent === "not_granted" ? (
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-[14px] bg-reviewsoft/60 px-4 py-3">
                    <p className="text-[14px] leading-6 text-review">
                      초대 코드를 잊었거나 10분이 지났다면 새 코드를 발급해 다시 보내 주세요.
                    </p>
                    <Link
                      to={`/children/${c.id}#guardians`}
                      className="text-[14px] font-bold text-review hover:underline"
                    >
                      초대 코드 보기 · 발급 ›
                    </Link>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
        </Card>
      </div>
    </>
  );
}
