import { useEffect, useState } from "react";
import { FileText, LogOut } from "lucide-react";
import { Link, useLoaderData, useNavigate, useRevalidator, useRouteLoaderData } from "react-router";
import { Busy, InstitutionChip } from "@/components/ui";
import { WithdrawAccount } from "@/components/WithdrawAccount";
import { useChildContext } from "@/components/parent/ChildContext";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Toggle } from "@/components/parent/Toggle";
import {
  loadOpenChecklistCount,
  getInstitutionShares,
  getParentChildren,
  getParentChildrenWithOwner,
  getPendingInstitutionRequests,
  isReady,
  loadIfReady,
  unlinkChild,
  updateConsent,
} from "@/lib/api";
import { signOut } from "@/lib/auth";
import { selectedChildOrInvite } from "@/lib/selectedChild";
import type { ConsentState, InstitutionShares } from "@/lib/types";
import type { clientLoader as parentSessionLoader } from "./session";

export async function clientLoader() {
  const { children, ownerUserId } = await getParentChildrenWithOwner();
  const child = selectedChildOrInvite(children);
  const childId = child.id;
  const [openChecklists, pendingRequests, shares] = await Promise.all([
    loadOpenChecklistCount(childId),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
    loadIfReady("institutionShares", () => getInstitutionShares(childId)),
  ]);
  return { child, openChecklists, pendingRequests, shares, ownerUserId };
}

export default function ParentSettingsPage() {
  const data = useLoaderData<typeof clientLoader>();
  return <ParentSettingsContent key={data.child.id} {...data} />;
}

function ParentSettingsContent({
  child,
  openChecklists,
  pendingRequests,
  shares,
  ownerUserId,
}: Awaited<ReturnType<typeof clientLoader>>) {
  const withdrawalEnabled =
    useRouteLoaderData<typeof parentSessionLoader>("routes/parent/session")?.withdrawalEnabled === true;
  const withdrawTarget = withdrawalEnabled && ownerUserId ? { userId: ownerUserId } : null;
  const guardianLabel = `${child.name} 보호자`;
  const { kids, selectChild } = useChildContext();
  const [institutions, setInstitutions] = useState(child.institutions);
  useEffect(() => setInstitutions(child.institutions), [child.institutions]);
  const sharesOf = new Map((shares ?? []).map((s) => [s.institution.id, s]));
  const pastShares = (shares ?? []).filter(
    (s) => !institutions.some((i) => i.institution.id === s.institution.id),
  );
  const [confirming, setConfirming] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [signingOut, setSigningOut] = useState(false);

  async function logout() {
    setSigningOut(true);
    await signOut();
    navigate("/parent/invite", { replace: true });
  }

  const [unlinking, setUnlinking] = useState(false);
  const [unlinkError, setUnlinkError] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);
  const navigate = useNavigate();
  const revalidator = useRevalidator();

  const setLocal = (instId: string, consent: ConsentState) =>
    setInstitutions((list) =>
      list.map((i) => (i.institution.id === instId ? { ...i, consent } : i)),
    );

  function grant(instId: string) {
    const query = new URLSearchParams({ childId: child.id, institutionId: instId, from: "settings" });
    navigate(`/parent/consent?${query}`);
  }

  async function revoke(instId: string) {
    setPending(true);
    setRevokeError(null);
    try {
      const result = await updateConsent(child.id, instId, { action: "revoke" });
      setLocal(instId, result.consent);
      setConfirming(null);
      revalidator.revalidate();
    } catch {
      setRevokeError("권한을 끄지 못했어요. 새로고침한 뒤 다시 시도해 주세요.");
    } finally {
      setPending(false);
    }
  }

  async function unlink() {
    setPending(true);
    setUnlinkError(null);
    try {
      await unlinkChild(child.id);
      const remaining = await getParentChildren();
      if (remaining.length === 0) {
        navigate("/parent/invite", { replace: true });
        return;
      }
      await navigate("/parent", { replace: true });
      await revalidator.revalidate();
    } catch {
      setUnlinkError("연결을 끊지 못했어요. 새로고침한 뒤 다시 시도해 주세요.");
    } finally {
      setPending(false);
    }
  }

  const target = institutions.find((i) => i.institution.id === confirming);
  const needsCheck = openChecklists;
  const careCount = (child.care?.allergies.length ?? 0) + (child.care?.medications.length ?? 0);
  const careReady = isReady("careInfo");

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="설정"
        subtitle={guardianLabel}
        showChildSwitch
        alert={(pendingRequests ?? []).length > 0 || (needsCheck ?? 0) > 0}
      />

      <div className="flex flex-col gap-2.5">
        {kids.map((kid) => {
          const current = kid.id === child.id;
          return (
            <div
              key={kid.id}
              className={`flex items-center gap-3 rounded-2xl border px-4 py-4 ${current ? "border-accent" : "border-line"}`}
            >
              <span
                aria-hidden
                className="flex size-12 items-center justify-center rounded-full bg-surface2 text-[18px] font-bold text-ink2"
              >
                {kid.name.slice(0, 1)}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate font-bold">{kid.name}</span>
                <span className="block truncate text-[13px] text-muted">
                  {current ? "지금 보는 중 · " : ""}기관{" "}
                  {current ? institutions.length : kid.institutions.length}곳 연결
                </span>
              </span>
              {current ? (
                <button
                  onClick={() => {
                    setUnlinkError(null);
                    setUnlinking(true);
                  }}
                  className="tap shrink-0 rounded-2xl border border-line2 px-3 text-[13px] font-semibold text-block"
                >
                  연결 해제
                </button>
              ) : (
                <button
                  onClick={() => selectChild(kid.id)}
                  className="tap shrink-0 rounded-2xl border border-line2 px-3 text-[13px] font-semibold text-ink2"
                >
                  설정 보기
                </button>
              )}
            </div>
          );
        })}
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
                  <SharesLink shares={sharesOf.get(institution.id)} />
                </span>
                <Toggle
                  on={on}
                  label={`${institution.name} 권한`}
                  onChange={() => {
                    if (!on) return grant(institution.id);
                    setRevokeError(null);
                    setConfirming(institution.id);
                  }}
                />
              </div>
            );
          })}
          {(child.linkRequests ?? []).map(({ institution }) => (
            <div
              key={`request-${institution.id}`}
              className="flex items-center gap-3 rounded-2xl border border-dashed border-line2 px-4 py-3"
            >
              <InstitutionIcon type={institution.type} />
              <span className="min-w-0 flex-1">
                <InstitutionChip institution={institution} withName />
                <span className="mt-0.5 block text-[13px] text-muted">
                  수락 대기 중 · 기관이 수락하면 알림으로 알려 드려요
                </span>
              </span>
            </div>
          ))}
        </div>
        {pastShares.length > 0 ? (
          <div className="mt-4">
            <h3 className="mb-2 text-[14px] font-bold text-ink2">지금은 연결되지 않은 기관</h3>
            <div className="flex flex-col gap-2.5">
              {pastShares.map((s) => (
                <div
                  key={`past-${s.institution.id}`}
                  className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3"
                >
                  <InstitutionIcon type={s.institution.type} />
                  <span className="min-w-0 flex-1">
                    <InstitutionChip institution={s.institution} withName />
                    <SharesLink shares={s} />
                  </span>
                </div>
              ))}
            </div>
          </div>
        ) : null}
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
            <span className="text-[14px] text-muted">{careReady ? `${careCount}개` : "준비 중"} ›</span>
          </Link>
          <Link
            to="/parent/checklists"
            className="tap flex items-center justify-between px-4 py-3.5"
          >
            <span className="text-[15px]">준비물·확인사항</span>
            <span className="text-[14px] text-muted">
              {needsCheck === null ? "" : needsCheck > 0 ? `${needsCheck}건 남음 ` : "완료 "}›
            </span>
          </Link>
        </div>
      </div>

      <p className="text-[13px] leading-6 text-muted">
        권한을 끄면 그 기관은 받은 주간 리포트를 더 볼 수 없고, 그 기관 기록이 들어간 리포트도 다른
        기관에서 가려져요. 기관이 이미 쓴 기록은 기관에 남아요.
      </p>

      <div>
        <h2 className="mb-2 text-[15px] font-bold text-ink2">계정</h2>
        <div className="divide-y divide-line rounded-2xl border border-line">
          <Link to="/terms/tos" className="tap flex w-full items-center justify-between px-4 py-3.5">
            <span className="text-[15px]">약관 보기</span>
            <FileText aria-hidden size={17} strokeWidth={1.9} className="text-muted" />
          </Link>
          <button
            type="button"
            onClick={logout}
            disabled={signingOut}
            className="tap flex w-full items-center justify-between px-4 py-3.5 disabled:opacity-60"
          >
            <span className="text-[15px]">{signingOut ? <Busy>로그아웃 중…</Busy> : "로그아웃"}</span>
            <LogOut aria-hidden size={17} strokeWidth={1.9} className="text-muted" />
          </button>
          <WithdrawAccount audience="parent" trigger="row" account={withdrawTarget} />
        </div>
        <p className="mt-2 text-[13px] leading-6 text-muted">
          로그아웃해도 아이와의 연결과 동의는 그대로예요. 다시 카카오로 로그인하면 이어서 볼 수
          있어요.{withdrawTarget ? " 탈퇴하면 아이와의 연결이 모두 끊겨요." : ""}
        </p>
      </div>

      {unlinking ? (
        <div
          role="dialog"
          aria-modal
          className="fixed inset-0 z-10 flex items-end justify-center bg-ink/40"
        >
          <div className="w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] rounded-t-3xl bg-surface p-5 pb-7">
            <p className="mb-2 text-[18px] font-extrabold">{child.name}와의 연결을 끊을까요?</p>
            <p className="mb-5 text-[15px] leading-7 text-ink2">
              끊으면 이 아이의 기록과 소식을 더 볼 수 없어요.{" "}
              <b className="font-semibold">다시 연결하려면 기관에서 새 초대 코드를 받아야 해요.</b>
            </p>
            {unlinkError ? (
              <p role="alert" className="mb-3 rounded-2xl bg-blocksoft px-4 py-3 text-[14px] text-block">
                {unlinkError}
              </p>
            ) : null}
            <div className="flex gap-2">
              <button
                onClick={() => setUnlinking(false)}
                className="tap h-14 flex-1 rounded-2xl border border-line2 text-[16px] font-bold text-ink2"
              >
                취소
              </button>
              <button
                disabled={pending}
                onClick={unlink}
                className="tap h-14 flex-1 rounded-2xl bg-block text-[16px] font-bold text-white disabled:opacity-50"
              >
                {pending ? <Busy>처리 중…</Busy> : "연결 끊기"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {target ? (
        <div
          role="dialog"
          aria-modal
          aria-labelledby="revoke-title"
          aria-describedby="revoke-desc"
          className="fixed inset-0 z-10 flex items-end justify-center bg-ink/40"
        >
          <div className="flex max-h-[90dvh] w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] flex-col rounded-t-3xl bg-surface p-5 pb-7">
            <div className="min-h-0 overflow-y-auto">
              <p id="revoke-title" className="mb-2 text-[18px] font-extrabold">
                {target.institution.name} 권한을 끌까요?
              </p>
              <ul
                id="revoke-desc"
                className="mb-5 flex list-disc flex-col gap-1.5 pl-5 text-[15px] leading-7 text-ink2"
              >
                <li>
                  <b className="font-semibold">
                    이 기관은 받은 주간 리포트를 더 볼 수 없어요. 이 기관 기록이 들어간 리포트도 다른
                    기관에서 가려져요.
                  </b>
                </li>
                <li>
                  앞으로 이 기관이 올리는 기록은 {child.name} 기록으로 정리하지 않아요. 이미 처리
                  중이던 기록은 마저 정리될 수 있어요.
                </li>
                <li>이 기관이 이미 쓴 기록은 기관에 남아요.</li>
                <li>보호자님은 지금처럼 모든 기록과 리포트를 볼 수 있어요.</li>
                <li>다른 보호자가 이 기관에 한 공유 동의도 함께 꺼져요.</li>
                <li>다시 켜도, 기관이 이미 받았다가 가려진 리포트는 다시 보이지 않아요.</li>
              </ul>
              {revokeError ? (
                <p role="alert" className="mb-3 rounded-2xl bg-blocksoft px-4 py-3 text-[14px] text-block">
                  {revokeError}
                </p>
              ) : null}
            </div>
            <div className="flex shrink-0 gap-2">
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
                {pending ? <Busy>처리 중…</Busy> : "권한 끄기"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function SharesLink({ shares }: { shares: InstitutionShares | undefined }) {
  if (!shares || shares.shares.length === 0) return null;
  const hidden = shares.shares.filter((s) => !s.visible).length;
  return (
    <Link
      to={`/parent/settings/shares/${encodeURIComponent(shares.institution.id)}`}
      className="mt-1 block w-fit text-[13px] font-semibold text-accentink"
    >
      받은 리포트 {shares.shares.length}편{hidden > 0 ? ` · 가려짐 ${hidden}` : ""} ›
    </Link>
  );
}
