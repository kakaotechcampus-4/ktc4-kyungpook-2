import { useState, useTransition } from "react";
import { useNavigate } from "react-router";
import { signIn, getSession } from "@/lib/auth";
import { updateConsent } from "@/lib/api";
import { INSTITUTION_SHARE_FIELDS, INVITING_INSTITUTION, PARENT_CHILD } from "@/lib/mock/data";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { StepProgress } from "@/components/parent/StepProgress";

/**
 * P-02 확인 · 동의 — 한 화면에서 아이 확인 + 기관 확인 + 공유 범위 동의를 끝낸다.
 *
 * 첫 가입 때는 초대한 기관을, 이후에는 새로 권한을 요청한 기관을 그대로 보여준다 —
 * 둘 다 "아직 not_granted 상태인 기관"을 찾아서 쓰기 때문에 화면 하나로 겸한다.
 */
export default function ParentConsentPage() {
  const pendingRec = PARENT_CHILD.institutions.find((i) => i.consent === "not_granted");
  const institution = pendingRec?.institution ?? INVITING_INSTITUTION;
  const { shared, notShared } = INSTITUTION_SHARE_FIELDS[institution.type];

  const [consented, setConsented] = useState(false);
  const [mismatch, setMismatch] = useState(false);
  const [pending, start] = useTransition();
  const navigate = useNavigate();

  if (mismatch) {
    return (
      <div className="flex flex-col gap-5">
        <div className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3">
          <p className="mb-1 text-[16px] font-bold text-block">
            아이 정보가 일치하지 않아요
          </p>
          <p className="text-[15px] leading-7 text-ink2">
            화면에 표시된 이름과 생년월일이 우리 아이와 다르다면, 등록을 거부하고 기관에
            알려주세요.
          </p>
        </div>
        <div className="rounded-2xl border border-line bg-paper px-4 py-3">
          <p className="mb-1 text-[13px] font-semibold tracking-wider text-muted uppercase">
            기관이 등록한 정보
          </p>
          <p className="text-[16px] font-semibold">
            {PARENT_CHILD.name} · {PARENT_CHILD.birthDate.replaceAll("-", ".")}생
          </p>
        </div>
        <button className="tap h-14 w-full rounded-2xl bg-block px-4 text-[16px] font-bold text-white">
          정보가 달라요 · 반려하기
        </button>
        <button
          onClick={() => setMismatch(false)}
          className="tap h-14 w-full rounded-2xl border border-line2 px-4 text-[16px] font-bold text-ink2"
        >
          우리 아이가 맞아요
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5 pb-24">
      <StepProgress step={3} total={4} />

      <p className="rounded-2xl bg-accentsoft px-4 py-3 text-[15px] leading-6 text-accentink">
        <b className="font-semibold">{institution.name}</b>에서 {PARENT_CHILD.name} 학생을
        등록했어요
      </p>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">우리 아이 정보</h2>
        <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
          <span
            aria-hidden
            className="flex size-10 items-center justify-center rounded-full bg-surface2 text-[16px] font-semibold text-ink2"
          >
            {PARENT_CHILD.name.slice(0, 1)}
          </span>
          <span className="text-[16px] font-semibold">
            {PARENT_CHILD.name} · {PARENT_CHILD.birthDate.replaceAll("-", ".")}생
          </span>
        </div>
        <button
          onClick={() => setMismatch(true)}
          className="mt-2 text-[14px] text-muted underline"
        >
          우리 아이가 아니에요
        </button>
      </section>

      <section>
        <h2 className="mb-2 text-[16px] font-bold">이 기관은 어디인가요</h2>
        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-line px-4 py-3">
          <InstitutionIcon type={institution.type} />
          <InstitutionChip institution={institution} withName />
          <button className="ml-auto text-[14px] text-accentink underline">
            증빙서류 보기 ›
          </button>
        </div>
      </section>

      <section className="rounded-2xl bg-surface2 px-4 py-4">
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
      </section>

      <label className="tap flex cursor-pointer items-start gap-3 rounded-2xl border border-line px-4 py-3 has-checked:border-accent has-checked:bg-accentsoft">
        <input
          type="checkbox"
          checked={consented}
          onChange={() => setConsented((c) => !c)}
          className="mt-1 size-5 shrink-0 accent-accent"
        />
        <span className="text-[15px] leading-6">
          위 정보가 <b className="font-semibold">{institution.name}</b>에 공유되는 것에
          동의합니다. 언제든 [동의 관리]에서 회수할 수 있어요.
        </span>
      </label>

      {/* 하단 고정 — 스크롤 위치와 무관하게 동의 버튼이 보여야 한다 */}
      <div className="fixed bottom-0 left-1/2 w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] -translate-x-1/2 border-t border-line bg-surface px-4 py-3">
        <button
          onClick={() =>
            start(async () => {
              await updateConsent(PARENT_CHILD.id, institution.id, {
                allowed_fields: shared,
                action: "grant",
              });
              const { role } = await getSession();
              if (role === "parent") {
                navigate("/parent");
              } else {
                await signIn("parent");
                navigate("/parent/care-info?onboarding=1");
              }
            })
          }
          disabled={!consented || pending}
          className="tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:bg-surface2 disabled:text-muted"
        >
          {pending ? "확인 중…" : "동의하고 시작하기"}
        </button>
        {!consented ? (
          <p className="mt-1.5 text-center text-[14px] text-muted">
            동의해야 다음으로 넘어갈 수 있어요.
          </p>
        ) : null}
      </div>
    </div>
  );
}
