import { useState } from "react";
import { Link, useLoaderData, useNavigate, useRevalidator } from "react-router";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { Toggle } from "@/components/parent/Toggle";
import {
  getInstitutionRequests,
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
import type { ConsentState } from "@/lib/types";

export async function clientLoader() {
  const kids = await getParentChildren();
  const childId = readSelectedChildId(kids);
  const [{ child }, requests, pendingRequests, session] = await Promise.all([
    getParentHome(childId),
    // 기관 요청사항(준비물)은 서버에 생기기 전까지 null — mock 개수를 진짜 아이 설정에 띄우지 않는다
    loadIfReady("institutionRequests", () => getInstitutionRequests(childId)),
    loadIfReady("consentRequests", () => getPendingInstitutionRequests(childId)),
    getSession(),
  ]);
  // 실연동에서는 /auth/me 의 카카오 닉네임. 동의를 거부해 없으면 이름을 빼고 "보호자"만 쓴다
  const myName = isAuthMock() ? ME.name : (session.name ?? null);
  return { child, requests, pendingRequests, myName };
}

export default function ParentSettingsPage() {
  const data = useLoaderData<typeof clientLoader>();
  // 아이가 바뀌면 화면 상태(기관 목록·확인 대화·오류)를 새로 시작한다. 기관 목록은 처음 값만 잡는 상태라, 그대로 두면
  // 헤더에서 아이를 바꿔도 이전 아이의 기관이 남고 동의 끄기는 새 아이로 가서 화면과 요청이 어긋난다.
  return <ParentSettingsContent key={data.child.id} {...data} />;
}

function ParentSettingsContent({
  child,
  requests,
  pendingRequests,
  myName,
}: Awaited<ReturnType<typeof clientLoader>>) {
  const guardianLabel = myName ? `보호자 ${myName}` : "보호자";
  const [institutions, setInstitutions] = useState(child.institutions);
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
  const needsCheck = requests?.filter((r) => r.status === "needs_check").length ?? null;
  const careCount = (child.care?.allergies.length ?? 0) + (child.care?.medications.length ?? 0);
  // 돌봄 정보는 서버에 저장할 곳이 없다. "0개"는 "알레르기 없음"으로 읽히니 개수 대신 준비 중이라고 쓴다
  const careReady = isReady("careInfo");

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader
        title="설정"
        subtitle={guardianLabel}
        showChildSwitch
        alert={(pendingRequests ?? []).length > 0}
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
            to="/parent/institution-requests"
            className="tap flex items-center justify-between px-4 py-3.5"
          >
            <span className="text-[15px]">기관 요청사항</span>
            <span className="text-[14px] text-muted">
              {needsCheck === null ? "준비 중" : needsCheck > 0 ? `${needsCheck}건 남음` : "완료"} ›
            </span>
          </Link>
        </div>
      </div>

      <p className="text-[13px] leading-6 text-muted">
        권한을 끄면 그 기관은 더 이상 아이 기록을 볼 수 없습니다.
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
                {pending ? "처리 중…" : "연결 끊기"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

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
              <b className="font-semibold">이미 보낸 정보는 회수되지 않아요.</b> 다른 보호자가 켠
              권한도 함께 꺼져요.
            </p>
            {revokeError ? (
              <p role="alert" className="mb-3 rounded-2xl bg-blocksoft px-4 py-3 text-[14px] text-block">
                {revokeError}
              </p>
            ) : null}
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
