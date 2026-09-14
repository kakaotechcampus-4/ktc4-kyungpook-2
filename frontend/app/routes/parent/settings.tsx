import { useState } from "react";
import { Link, useLoaderData } from "react-router";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Toggle } from "@/components/parent/Toggle";
import {
  getInstitutionRequests,
  getParentChildren,
  getParentHome,
  getPendingInstitutionRequests,
  updateConsent,
} from "@/lib/api";
import { ME } from "@/lib/mock/data";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { ConsentState } from "@/lib/types";

export async function clientLoader() {
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [{ child }, requests, pendingRequests] = await Promise.all([
    getParentHome(childId),
    getInstitutionRequests(childId),
    getPendingInstitutionRequests(childId),
  ]);
  return { child, requests, pendingRequests };
}

export default function ParentSettingsPage() {
  const { child, requests, pendingRequests } = useLoaderData<typeof clientLoader>();
  const [institutions, setInstitutions] = useState(child.institutions);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const setLocal = (instId: string, consent: ConsentState) =>
    setInstitutions((list) =>
      list.map((i) => (i.institution.id === instId ? { ...i, consent } : i)),
    );

  async function grant(instId: string) {
    await updateConsent(child.id, instId, { allowed_fields: [], action: "grant" });
    setLocal(instId, "granted");
  }

  async function revoke(instId: string) {
    setPending(true);
    try {
      await updateConsent(child.id, instId, { allowed_fields: [], action: "revoke" });
      setLocal(instId, "revoked");
      setConfirming(null);
    } finally {
      setPending(false);
    }
  }

  const target = institutions.find((i) => i.institution.id === confirming);
  const needsCheck = requests.filter((r) => r.status === "needs_check").length;
  const careCount = (child.care?.allergies.length ?? 0) + (child.care?.medications.length ?? 0);

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="설정"
        subtitle={`보호자 ${ME.name}`}
        showChildSwitch
        alert={pendingRequests.length > 0}
      />

      <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-4">
        <span
          aria-hidden
          className="flex size-12 items-center justify-center rounded-full bg-surface2 text-[18px] font-bold text-ink2"
        >
          {child.name.slice(0, 1)}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate font-bold">{child.name}</span>
          <span className="block truncate text-[13px] text-muted">
            보호자 {ME.name}, 기관 {institutions.length}곳 연결
          </span>
        </span>
        <button className="tap shrink-0 rounded-2xl border border-line2 px-3 text-[13px] font-semibold text-ink2">
          수정
        </button>
      </div>

      <Link
        to="/parent/invite"
        className="tap flex items-center justify-center gap-2 rounded-2xl border border-dashed border-line2 px-4 text-[15px] font-semibold text-accentink hover:border-accent"
      >
        + 아이 추가
      </Link>

      <div>
        <h2 className="mb-2 text-[15px] font-bold text-ink2">기관 권한</h2>
        <div className="flex flex-col gap-2.5">
          {institutions.map(({ institution, consent }) => {
            const on = consent === "granted";
            return (
              <div
                key={institution.id}
                className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3"
              >
                <InstitutionIcon type={institution.type} />
                <span className="min-w-0 flex-1">
                  <InstitutionChip institution={institution} withName />
                  <span className="mt-0.5 block text-[13px] text-muted">
                    {on ? "권한 켜짐" : "권한 꺼짐"}
                  </span>
                </span>
                <Toggle
                  on={on}
                  label={`${institution.name} 권한`}
                  onChange={() => (on ? setConfirming(institution.id) : grant(institution.id))}
                />
              </div>
            );
          })}
        </div>
        <Link
          to="/parent/consent/manage/add"
          className="tap mt-2.5 flex items-center justify-center gap-2 rounded-2xl border border-dashed border-line2 px-4 text-[15px] font-semibold text-accentink hover:border-accent"
        >
          + 기관 직접 추가
        </Link>
      </div>

      <div>
        <h2 className="mb-2 text-[15px] font-bold text-ink2">아이 정보</h2>
        <div className="rounded-2xl border border-line">
          <Link
            to="/parent/care-info"
            className="tap flex items-center justify-between border-b border-line px-4 py-3.5"
          >
            <span className="text-[15px]">알레르기와 복용약</span>
            <span className="text-[14px] text-muted">{careCount}개 ›</span>
          </Link>
          <Link
            to="/parent/care-info"
            className="tap flex items-center justify-between border-b border-line px-4 py-3.5"
          >
            <span className="text-[15px]">주간 스케줄</span>
            <span className="text-[14px] text-muted">
              {child.care?.weeklySchedule.length ?? 0}건 ›
            </span>
          </Link>
          <Link
            to="/parent/institution-requests"
            className="tap flex items-center justify-between px-4 py-3.5"
          >
            <span className="text-[15px]">기관 요청사항</span>
            <span className="text-[14px] text-muted">
              {needsCheck > 0 ? `${needsCheck}건 남음` : "완료"} ›
            </span>
          </Link>
        </div>
      </div>

      <p className="text-[13px] leading-6 text-muted">
        권한을 끄면 그 기관은 더 이상 아이 기록을 볼 수 없습니다.
      </p>

      {target ? (
        <div
          role="dialog"
          aria-modal
          className="fixed inset-0 z-10 flex items-end justify-center bg-ink/40"
        >
          <div className="w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] rounded-t-3xl bg-surface p-5 pb-7">
            <p className="mb-2 text-[18px] font-extrabold">
              {target.institution.name} 권한을 끌까요?
            </p>
            <p className="mb-5 text-[15px] leading-7 text-ink2">
              끄면 앞으로 이 기관에 새로운 정보가 전달되지 않아요.{" "}
              <b className="font-semibold">이미 보낸 정보는 회수되지 않아요.</b>
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setConfirming(null)}
                className="tap h-14 flex-1 rounded-2xl border border-line2 text-[16px] font-bold text-ink2"
              >
                취소
              </button>
              <button
                disabled={pending}
                onClick={() => revoke(target.institution.id)}
                className="tap h-14 flex-1 rounded-2xl bg-block text-[16px] font-bold text-white disabled:opacity-50"
              >
                {pending ? "처리 중…" : "권한 끄기"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
