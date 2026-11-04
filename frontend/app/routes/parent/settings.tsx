import { useEffect, useState } from "react";
import { Link, useLoaderData, useNavigate, useRevalidator } from "react-router";
import { Busy, InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Toggle } from "@/components/parent/Toggle";
import {
  loadOpenChecklistCount,
  getInstitutionShares,
  getParentChildren,
  getParentHome,
  getPendingInstitutionRequests,
  isReady,
  loadIfReady,
  unlinkChild,
  updateConsent,
} from "@/lib/api";
import { getSession, isAuthMock } from "@/lib/auth";
import { ME } from "@/lib/mock/data";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { ConsentState, InstitutionShares } from "@/lib/types";

export async function clientLoader() {
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [{ child }, openChecklists, pendingRequests, shares, session] = await Promise.all([
    getParentHome(childId),
    // 확인 안 한 준비물·확인사항 수. 못 받으면 null — 개수만 숨기고 설정은 그대로 연다
    loadOpenChecklistCount(childId),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
    // 기관 줄의 "받은 리포트 N편". 서버에 없으면 null — 줄에 아무것도 붙이지 않는다
    loadIfReady("institutionShares", () => getInstitutionShares(childId)),
    getSession(),
  ]);
  // 실연동에서는 /auth/me 의 카카오 닉네임. 동의를 거부해 없으면 이름을 빼고 "보호자"만 쓴다
  const myName = isAuthMock() ? ME.name : (session.name ?? null);
  return { child, openChecklists, pendingRequests, shares, myName };
}

export default function ParentSettingsPage() {
  const data = useLoaderData<typeof clientLoader>();
  // 아이가 바뀌면 화면 상태(기관 목록·확인 대화·오류)를 새로 시작한다. 기관 목록은 처음 값만 잡는 상태라, 그대로 두면
  // 헤더에서 아이를 바꿔도 이전 아이의 기관이 남고 동의 끄기는 새 아이로 가서 화면과 요청이 어긋난다.
  return <ParentSettingsContent key={data.child.id} {...data} />;
}

function ParentSettingsContent({
  child,
  openChecklists,
  pendingRequests,
  shares,
  myName,
}: Awaited<ReturnType<typeof clientLoader>>) {
  const guardianLabel = myName ? `보호자 ${myName}` : "보호자";
  const [institutions, setInstitutions] = useState(child.institutions);
  // 같은 아이에서 다시 불러오면(권한 끄기 뒤 등) 서버 목록으로 맞춘다. 처음 값만 잡아 두면 명부에서 빠진 기관이
  // 토글과 함께 남아 아래 "지금은 연결되지 않은 기관" 과 겹친다. 끄기 직후의 즉시 표시(setLocal)는 이 값이 오면 덮인다
  useEffect(() => setInstitutions(child.institutions), [child.institutions]);
  const sharesOf = new Map((shares ?? []).map((s) => [s.institution.id, s]));
  const pastShares = (shares ?? []).filter(
    (s) => !institutions.some((i) => i.institution.id === s.institution.id),
  );
  const [confirming, setConfirming] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [unlinking, setUnlinking] = useState(false);
  const [unlinkError, setUnlinkError] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);
  const navigate = useNavigate();
  const revalidator = useRevalidator();

  const setLocal = (instId: string, consent: ConsentState) =>
    setInstitutions((list) =>
      list.map((i) => (i.institution.id === instId ? { ...i, consent } : i)),
    );

  /**
   * 다시 켤 때는 동의 화면(P-02)을 거친다 — 무엇이 공유되는지 보고 동의해야 한다(Consent First).
   * 공유 항목도 그 화면의 미리보기가 정한다. 동의를 마치면 이 화면으로 돌아온다.
   */
  function grant(instId: string) {
    const query = new URLSearchParams({ childId: child.id, institutionId: instId, from: "settings" });
    navigate(`/parent/consent?${query}`);
  }

  /** G-41 revoke. 이 기관에 대한 다른 보호자의 동의도 함께 꺼진다 — 상태는 서버 응답으로 고친다 */
  async function revoke(instId: string) {
    setPending(true);
    setRevokeError(null);
    try {
      const result = await updateConsent(child.id, instId, { action: "revoke" });
      setLocal(instId, result.consent);
      setConfirming(null);
      // 끈 기관(과 그 기관 기록이 들어간 리포트를 받은 다른 기관)의 "가려짐" 개수를 바로 고친다
      revalidator.revalidate();
    } catch {
      setRevokeError("권한을 끄지 못했어요. 새로고침한 뒤 다시 시도해 주세요.");
    } finally {
      setPending(false);
    }
  }

  /**
   * G-05. 끊으면 이 아이는 목록에서 빠지니 홈으로 돌아가 남은 아이를 다시 고른다.
   *
   * 아이 목록은 입구(guard.tsx)가 들고 있는데, 같은 레이아웃 안의 이동이라 입구의 loader 는 다시 돌지 않는다.
   * 그대로 두면 아이 전환 목록에 끊은 아이가 남으니 이동 뒤 한 번 더 불러온다. 남은 아이가 없으면 홈 대신
   * 초대 코드 화면으로 바로 간다 — 홈 loader 는 고를 아이가 없어 빈 childId 로 요청하게 된다.
   */
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
  // 확인 안 한 것은 서버가 상한 없이 모두 주므로 길이가 곧 전체 수다. null 이면 모른다 — 개수를 띄우지 않는다
  const needsCheck = openChecklists;
  const careCount = (child.care?.allergies.length ?? 0) + (child.care?.medications.length ?? 0);
  // 돌봄 정보는 서버에 저장할 곳이 없다. "0개"는 "알레르기 없음"으로 읽히니 개수 대신 준비 중이라고 쓴다
  const careReady = isReady("careInfo");

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="설정"
        subtitle={guardianLabel}
        showChildSwitch
        alert={(pendingRequests ?? []).length > 0 || (needsCheck ?? 0) > 0}
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
            {guardianLabel}, 기관 {institutions.length}곳 연결
          </span>
        </span>
        <button className="tap shrink-0 rounded-2xl border border-line2 px-3 text-[13px] font-semibold text-ink2">
          수정
        </button>
        <button
          onClick={() => {
            setUnlinkError(null);
            setUnlinking(true);
          }}
          className="tap shrink-0 rounded-2xl border border-line2 px-3 text-[13px] font-semibold text-block"
        >
          연결 해제
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
            {/* 명부에서 빠져 권한을 켜고 끌 수 없는 기관. 받은 리포트가 가려졌는지는 볼 수 있어야 한다 */}
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
          {/* 안내가 길어 작은 화면 · 글자 확대에서 잘리지 않게 높이를 묶고 안내만 스크롤한다. 버튼은 늘 보인다 */}
          <div className="flex max-h-[90dvh] w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] flex-col rounded-t-3xl bg-surface p-5 pb-7">
            <div className="min-h-0 overflow-y-auto">
              <p id="revoke-title" className="mb-2 text-[18px] font-extrabold">
                {target.institution.name} 권한을 끌까요?
              </p>
              {/* 지금 동작보다 강하게 말하지 않는다. 받기만 한 기관을 끄면 쓴 기관은 계속 보므로 "양쪽 모두"가
                  아니고, 이미 시작된 매칭은 동의를 다시 보지 않으며, 다시 켜도 거둔 수신함 권한은 돌아오지 않는다 */}
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

/** 기관 줄의 "받은 리포트 N편 · 가려짐 K ›". 받은 것이 없으면 아무것도 붙이지 않는다 */
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
