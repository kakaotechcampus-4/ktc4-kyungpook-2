import { useState } from "react";
import { Hand } from "lucide-react";
import { useLoaderData, useRevalidator } from "react-router";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import {
  declineInstitutionRequest,
  getParentChildren,
  getPendingInstitutionRequests,
  updateConsent,
} from "@/lib/api";
import { INSTITUTION_SHARE_FIELDS } from "@/lib/mock/data";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader() {
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const pending = await getPendingInstitutionRequests(childId);
  return { childId, pending };
}

export default function NotificationsPage() {
  const { childId, pending } = useLoaderData<typeof clientLoader>();
  const [busy, setBusy] = useState<string | null>(null);
  const revalidator = useRevalidator();

  async function approve(instId: string) {
    setBusy(instId);
    try {
      await updateConsent(childId, instId, { allowed_fields: [], action: "grant" });
      revalidator.revalidate();
    } finally {
      setBusy(null);
    }
  }

  async function decline(instId: string) {
    setBusy(instId);
    try {
      await declineInstitutionRequest(childId, instId);
      revalidator.revalidate();
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="알림" subtitle={`확인이 필요한 요청 ${pending.length}건`} back />

      {pending.length === 0 ? (
        <p className="py-10 text-center text-[15px] text-muted">확인할 알림이 없습니다.</p>
      ) : (
        pending.map(({ institution }) => {
          const { shared, notShared } = INSTITUTION_SHARE_FIELDS[institution.type];
          const isBusy = busy === institution.id;
          return (
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
                <div className="mb-3 flex items-center gap-3">
                  <InstitutionIcon type={institution.type} />
                  <span>
                    <span className="block font-bold">{institution.name}</span>
                  </span>
                </div>

                <div className="rounded-2xl bg-surface2 px-4 py-4">
                  <p className="mb-2 text-[13px] font-bold text-accentink">공유되는 정보</p>
                  <ul className="flex flex-col gap-2">
                    {shared.map((label) => (
                      <li key={label} className="text-[15px]">
                        {label}
                      </li>
                    ))}
                  </ul>
                  <p className="mt-3 text-[14px] leading-6 text-muted">
                    {notShared.join(", ")}는 공유되지 않습니다.
                  </p>
                </div>

                <div className="mt-4 flex gap-2">
                  <button
                    disabled={isBusy}
                    onClick={() => decline(institution.id)}
                    className="tap h-14 flex-1 rounded-2xl border border-line2 text-[16px] font-bold text-ink2 disabled:opacity-50"
                  >
                    거절
                  </button>
                  <button
                    disabled={isBusy}
                    onClick={() => approve(institution.id)}
                    className="tap h-14 flex-1 rounded-2xl bg-accent text-[16px] font-bold text-white disabled:opacity-50"
                  >
                    {isBusy ? "처리 중…" : "확인 후 권한 부여"}
                  </button>
                </div>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}
