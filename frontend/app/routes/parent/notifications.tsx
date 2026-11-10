import { ClipboardList, Hand } from "lucide-react";
import { Link, useLoaderData, useNavigate } from "react-router";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getParentChildren, getPendingInstitutionRequests, isReady, loadOpenChecklistCount } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader() {
  if (!isReady("consentRequests")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [pending, openChecklists] = await Promise.all([
    getPendingInstitutionRequests(childId),
    loadOpenChecklistCount(childId),
  ]);
  return { childId, pending, openChecklists };
}

export default function NotificationsPageRoute() {
  if (!isReady("consentRequests")) return <ParentNotReadyPage title="알림" back />;
  return <NotificationsPage />;
}

function NotificationsPage() {
  const { childId, pending, openChecklists } = useLoaderData<typeof clientLoader>()!;
  const checklistsKnown = openChecklists !== null;
  const total = pending.length + (openChecklists ?? 0);
  const navigate = useNavigate();

  function review(institutionId: string) {
    const query = new URLSearchParams({ childId, institutionId, from: "notifications" });
    navigate(`/parent/consent?${query}`);
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="알림"
        subtitle={checklistsKnown ? `확인이 필요한 요청 ${total}건` : `기관 권한 요청 ${pending.length}건`}
        back
      />

      {!checklistsKnown ? (
        <p className="rounded-2xl bg-surface2 px-4 py-3 text-[14px] leading-6 text-ink2">
          준비물·확인사항을 지금 불러오지 못했어요. 잠시 후 다시 열어 주세요.
        </p>
      ) : total === 0 ? (
        <p className="py-10 text-center text-[15px] text-muted">확인할 알림이 없어요.</p>
      ) : null}

      {openChecklists !== null && openChecklists > 0 ? (
        <Link
          to="/parent/checklists"
          className="tap flex items-center gap-2 rounded-2xl border border-line px-4 py-3.5 text-[15px] font-semibold"
        >
          <ClipboardList aria-hidden size={20} strokeWidth={1.75} className="shrink-0 text-human" />
          <span className="flex-1">확인할 준비물·확인사항 {openChecklists}건</span>
          <span aria-hidden className="text-muted">
            ›
          </span>
        </Link>
      ) : null}

      {pending.length === 0 ? null : (
        pending.map(({ institution }) => (
          <div key={institution.id} className="flex flex-col gap-4">
            <div className="flex items-start gap-2 rounded-2xl bg-humansoft px-4 py-3 text-human">
              <Hand size={20} strokeWidth={1.75} className="mt-0.5 shrink-0" />
              <div>
                <p className="font-bold">새 기관이 권한을 요청했어요</p>
                <p className="text-[14px] leading-6">
                  허락하기 전에 무엇이 공유되는지 확인해 주세요.
                </p>
              </div>
            </div>

            <div className="rounded-2xl border border-line px-4 py-4">
              <div className="mb-4 flex items-center gap-3">
                <InstitutionIcon type={institution.type} />
                <span className="block font-bold">{institution.name}</span>
              </div>
              <button
                onClick={() => review(institution.id)}
                className="tap h-14 w-full rounded-2xl bg-ink text-[16px] font-bold text-paper"
              >
                공유 내용 확인하고 권한 주기
              </button>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
