import Link from "next/link";
import { ConsentStatus, InstitutionChip } from "@/components/ui";
import { getParentHome } from "@/lib/api";

export default async function ParentHomePage() {
  const { child, activity } = await getParentHome();
  const pendingRequest = child.institutions.find((i) => i.consent === "not_granted");

  return (
    <div className="flex flex-col gap-6">
      {pendingRequest ? (
        <Link
          href="/parent/consent"
          className="flex items-center gap-2 rounded border border-accent/40 bg-accentsoft px-3 py-2.5 text-[15px] leading-6 text-accentink"
        >
          <span className="min-w-0 flex-1">
            <b className="font-semibold">{pendingRequest.institution.name}</b>가 새로 연결을
            요청했어요
          </span>
          <span aria-hidden className="font-semibold">
            확인하기 ›
          </span>
        </Link>
      ) : null}

      <section className="flex items-center gap-3 rounded border border-line px-4 py-4">
        <span
          aria-hidden
          className="flex size-12 items-center justify-center rounded-full bg-surface2 text-[18px] font-semibold text-ink2"
        >
          {child.name.slice(0, 1)}
        </span>
        <span>
          <span className="block text-[18px] font-bold">{child.name}</span>
          <span className="block text-[14px] text-muted tabular-nums">
            {child.birthDate.replaceAll("-", ".")}생
          </span>
        </span>
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">연결된 기관</h2>
        <ul className="flex flex-col divide-y divide-line rounded border border-line">
          {child.institutions.map(({ institution, consent }) => (
            <li
              key={institution.id}
              className="flex flex-wrap items-center gap-2 px-4 py-3"
            >
              <InstitutionChip institution={institution} withName />
              <span className="ml-auto">
                <ConsentStatus state={consent} />
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">최근 공유 활동</h2>
        <ul className="flex flex-col divide-y divide-line rounded border border-line">
          {activity.map((a) => (
            <li key={a.id} className="px-4 py-3">
              <p className="text-[15px] leading-6">{a.text}</p>
              <p className="mt-0.5 text-[13px] text-muted">{a.at}</p>
            </li>
          ))}
        </ul>
      </section>

      <p className="text-[14px] leading-6 text-muted">
        궁금한 점은{" "}
        <Link href="/parent/consent/manage" className="text-accentink underline">
          동의 관리
        </Link>
        에서 언제든 확인할 수 있어요.
      </p>
    </div>
  );
}
