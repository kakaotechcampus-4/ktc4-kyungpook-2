import { Hand } from "lucide-react";
import { useLoaderData, useNavigate } from "react-router";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getParentChildren, getPendingInstitutionRequests, isReady } from "@/lib/api";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader() {
  // 서버에 아직 없다. mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("consentRequests")) return null;
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const pending = await getPendingInstitutionRequests(childId);
  return { childId, pending };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function NotificationsPageRoute() {
  if (!isReady("consentRequests")) return <ParentNotReadyPage title="알림" back />;
  return <NotificationsPage />;
}

/**
 * 대기 중 기관 권한 요청(G-40). 권한은 여기서 바로 주지 않고 동의 화면(P-02)으로 보낸다 — 무엇이
 * 공유되는지 서버 미리보기로 보고 동의해야 한다(Consent First). 동의를 마치면 이 화면으로 돌아온다.
 * 요청 거절(G-42)은 MVP 에서 뺐다. 동의하지 않을 기관이면 알림이 남지만 설정에서 언제든 켤 수 있다.
 */
function NotificationsPage() {
  const { childId, pending } = useLoaderData<typeof clientLoader>()!;
  const navigate = useNavigate();

  function review(institutionId: string) {
    const query = new URLSearchParams({ childId, institutionId, from: "notifications" });
    navigate(`/parent/consent?${query}`);
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="알림" subtitle={`확인이 필요한 요청 ${pending.length}건`} back />

      {pending.length === 0 ? (
        <p className="py-10 text-center text-[15px] text-muted">확인할 알림이 없습니다.</p>
      ) : (
        pending.map(({ institution }) => (
          <div key={institution.id} className="flex flex-col gap-4">
            <div className="flex items-start gap-2 rounded-2xl border-l-4 border-human bg-humansoft px-4 py-3 text-human">
              <Hand size={20} strokeWidth={1.75} className="mt-0.5 shrink-0" />
              <div>
                <p className="font-bold">새 기관이 권한을 요청했습니다</p>
                <p className="text-[14px] leading-6">
                  허락하기 전에 어떤 정보가 공유되는지 확인해주세요.
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
                className="tap h-14 w-full rounded-2xl bg-accent text-[16px] font-bold text-white"
              >
                공유 항목 확인하고 권한 주기
              </button>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
