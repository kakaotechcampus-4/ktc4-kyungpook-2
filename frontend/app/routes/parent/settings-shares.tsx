import { Link, useLoaderData } from "react-router";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { getInstitutionShares, getParentChildren, isReady } from "@/lib/api";
import { formatMonthDay } from "@/lib/datetime";
import { readSelectedChildId } from "@/lib/selectedChild";

export async function clientLoader({ params }: { params: { institutionId: string } }) {
  if (!isReady("institutionShares")) return null;
  const kids = await getParentChildren();
  const shares = await getInstitutionShares(readSelectedChildId(kids));
  return { group: shares.find((s) => s.institution.id === params.institutionId) ?? null };
}

export default function SettingsSharesRoute() {
  if (!isReady("institutionShares")) return <ParentNotReadyPage title="공유 내역" back />;
  return <SettingsSharesPage />;
}

function SettingsSharesPage() {
  const data = useLoaderData<typeof clientLoader>();
  const group = data?.group ?? null;

  if (!group) {
    return (
      <div className="flex flex-col gap-5">
        <ParentPageHeader title="공유 내역" back />
        <p className="rounded-2xl border border-dashed border-line2 px-4 py-6 text-center text-[14px] text-muted">
          받은 리포트가 없어요.
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title={`${group.institution.name} 공유 내역`} back />

      <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
        <InstitutionIcon type={group.institution.type} />
        <InstitutionChip institution={group.institution} withName />
      </div>

      <ul className="flex flex-col divide-y divide-line rounded-2xl border border-line">
        {group.shares.map((share) => (
          <li key={share.insightId}>
            <Link to={`/parent/report/${encodeURIComponent(share.insightId)}`} className="tap block px-4 py-3 hover:bg-surface2">
              <p className="text-[15px] font-semibold leading-6">{share.period} 리포트</p>
              <p className="mt-0.5 text-[13px] text-muted">
                {share.from ? `${share.from.name}에서 정리 · ` : ""}
                {formatMonthDay(share.grantedAt)} 열림
              </p>
              <p
                className={`mt-1.5 inline-block rounded-full px-2 py-0.5 text-[12px] font-semibold ${
                  share.visible ? "bg-accentsoft text-accentink" : "bg-surface2 text-muted"
                }`}
              >
                {share.visible
                  ? "지금 볼 수 있음"
                  : share.hiddenAt
                    ? `가려짐 · ${formatMonthDay(share.hiddenAt)}`
                    : "가려짐"}
              </p>
            </Link>
          </li>
        ))}
      </ul>

      <p className="text-[13px] leading-6 text-muted">
        이 기관에 열린 주간 리포트예요. 이 기관 권한을 끄거나 아이가 이 기관 명부에서 빠지면 가려지고, 다시 켜도 가려진
        리포트는 돌아오지 않아요.
      </p>
    </div>
  );
}
