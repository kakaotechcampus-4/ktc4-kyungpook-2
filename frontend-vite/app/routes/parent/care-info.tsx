import { useState } from "react";
import { useLoaderData, useNavigate } from "react-router";
import { getParentChildren, getParentHome, updateChildCare } from "@/lib/api";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { StepProgress } from "@/components/parent/StepProgress";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { ChildCareInfo } from "@/lib/types";

const EMPTY_CARE: ChildCareInfo = {
  welfareCard: false,
  allergies: [],
  medications: [],
  weeklySchedule: [],
};

const COMMON_ALLERGIES = ["우유와 유제품", "땅콩", "계란", "갑각류"];

function Pill({ required }: { required: boolean }) {
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-[12px] font-bold ${
        required ? "bg-accentsoft text-accentink" : "bg-surface2 text-muted"
      }`}
    >
      {required ? "필수" : "선택"}
    </span>
  );
}

export async function clientLoader({ request }: { request: Request }) {
  const onboarding = new URL(request.url).searchParams.get("onboarding") === "1";
  const kids = await getParentChildren();
  const { child } = await getParentHome(readSelectedChildId(kids));
  return { childId: child.id, childName: child.name, care: child.care ?? EMPTY_CARE, onboarding };
}

/**
 * 온보딩 마지막 단계 — 동의 직후, 기관에 전달할 돌봄 정보를 보호자가 직접 채운다.
 * 여기서 입력한 항목은 기관별 공유 범위(INSTITUTION_SHARE_FIELDS)에 맞춰 변환되어 전달된다.
 */
export default function CareInfoPage() {
  const { childId, childName, care: initial, onboarding } = useLoaderData<typeof clientLoader>();
  const [care, setCare] = useState<ChildCareInfo>(initial);
  const [allergyDraft, setAllergyDraft] = useState("");
  const [medName, setMedName] = useState("");
  const [medTime, setMedTime] = useState("");
  const [saving, setSaving] = useState(false);
  const navigate = useNavigate();

  const toggleCommonAllergy = (label: string) =>
    setCare((c) =>
      c.allergies.includes(label)
        ? { ...c, allergies: c.allergies.filter((a) => a !== label) }
        : { ...c, allergies: [...c.allergies, label] },
    );

  const addAllergy = () => {
    if (!allergyDraft.trim()) return;
    setCare((c) => ({ ...c, allergies: [...c.allergies, allergyDraft.trim()] }));
    setAllergyDraft("");
  };
  const removeAllergy = (i: number) =>
    setCare((c) => ({ ...c, allergies: c.allergies.filter((_, idx) => idx !== i) }));

  const addMedication = () => {
    if (!medName.trim()) return;
    setCare((c) => ({
      ...c,
      medications: [...c.medications, { name: medName.trim(), time: medTime.trim() || "-" }],
    }));
    setMedName("");
    setMedTime("");
  };
  const removeMedication = (i: number) =>
    setCare((c) => ({ ...c, medications: c.medications.filter((_, idx) => idx !== i) }));

  async function save() {
    setSaving(true);
    try {
      await updateChildCare(childId, care);
      navigate(onboarding ? "/parent" : "/parent/settings");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-6 pb-24">
      {onboarding ? (
        <>
          <StepProgress step={4} total={4} />
          <div>
            <h1 className="mb-1.5 text-[22px] font-extrabold tracking-tight">아이 상세정보</h1>
            <p className="text-[15px] leading-7 text-ink2">
              알레르기와 복용약을 알려주시면 기관이 같은 기준으로 돌봅니다.
            </p>
          </div>
        </>
      ) : (
        <ParentPageHeader title="아이 상세정보" subtitle={childName} back />
      )}

      <section className="rounded-2xl border border-line px-4 py-4">
        <div className="mb-2.5 flex items-center gap-2">
          <h2 className="text-[16px] font-bold">식단과 알레르기</h2>
          <Pill required />
        </div>
        <div className="mb-3 flex flex-wrap gap-2">
          {COMMON_ALLERGIES.map((label) => {
            const on = care.allergies.includes(label);
            return (
              <button
                key={label}
                type="button"
                onClick={() => toggleCommonAllergy(label)}
                className={`tap rounded-2xl px-4 text-[14px] font-semibold transition-colors ${
                  on ? "bg-accent text-white" : "border border-line2 text-ink2 hover:bg-surface2"
                }`}
              >
                {label}
              </button>
            );
          })}
        </div>

        {care.allergies.filter((a) => !COMMON_ALLERGIES.includes(a)).length > 0 ? (
          <ul className="mb-3 flex flex-wrap gap-2">
            {care.allergies.map((a, i) =>
              COMMON_ALLERGIES.includes(a) ? null : (
                <li
                  key={a + i}
                  className="flex items-center gap-1.5 rounded-full border border-line2 bg-surface2 px-3 py-1.5 text-[14px]"
                >
                  {a}
                  <button
                    onClick={() => removeAllergy(i)}
                    aria-label={`${a} 삭제`}
                    className="text-muted hover:text-block"
                  >
                    ×
                  </button>
                </li>
              ),
            )}
          </ul>
        ) : null}

        <label className="mb-1.5 block text-[14px] font-semibold text-ink2">직접 입력</label>
        <div className="flex gap-2">
          <input
            value={allergyDraft}
            onChange={(e) => setAllergyDraft(e.target.value)}
            placeholder="예: 갑각류(새우)"
            className="tap flex-1 rounded-2xl border border-line2 px-4 text-[15px] outline-none focus:border-accent"
          />
          <button
            onClick={addAllergy}
            type="button"
            className="tap shrink-0 rounded-2xl border border-line2 px-4 text-[14px] font-semibold text-ink2 hover:bg-surface2"
          >
            추가
          </button>
        </div>
      </section>

      <section className="rounded-2xl border border-line px-4 py-4">
        <div className="mb-2.5 flex items-center gap-2">
          <h2 className="text-[16px] font-bold">지병과 복용약</h2>
          <Pill required={false} />
        </div>
        {care.medications.length > 0 ? (
          <ul className="mb-3 flex flex-col gap-2">
            {care.medications.map((m, i) => (
              <li
                key={m.name + i}
                className="flex items-center justify-between gap-2 rounded-2xl bg-surface2 px-4 py-3 text-[15px]"
              >
                <span>
                  <span className="font-semibold">{m.name}</span>{" "}
                  <span className="text-muted">· {m.time}</span>
                </span>
                <button
                  onClick={() => removeMedication(i)}
                  aria-label={`${m.name} 삭제`}
                  className="text-muted hover:text-block"
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        <div className="grid grid-cols-2 gap-2">
          <input
            value={medName}
            onChange={(e) => setMedName(e.target.value)}
            placeholder="약 이름"
            className="tap rounded-2xl border border-line2 px-4 text-[15px] outline-none focus:border-accent"
          />
          <input
            value={medTime}
            onChange={(e) => setMedTime(e.target.value)}
            placeholder="복용 시간 (예: 점심 식후)"
            className="tap rounded-2xl border border-line2 px-4 text-[15px] outline-none focus:border-accent"
          />
        </div>
        <button
          onClick={addMedication}
          type="button"
          className="tap mt-2 w-full rounded-2xl border border-dashed border-line2 px-4 text-[14px] font-semibold text-accentink hover:border-accent"
        >
          + 약 추가
        </button>
      </section>

      <section className="rounded-2xl border border-line px-4 py-4">
        <div className="mb-2.5 flex items-center justify-between">
          <h2 className="text-[16px] font-bold">복지카드</h2>
          <Pill required={false} />
        </div>
        {care.welfareCard ? (
          <p className="rounded-2xl border border-pass/40 bg-passsoft px-4 py-3 text-[15px] text-pass">
            복지카드 사진 등록 완료
          </p>
        ) : (
          <button
            onClick={() => setCare((c) => ({ ...c, welfareCard: true }))}
            className="tap w-full rounded-2xl border border-dashed border-line2 px-4 py-4 text-[15px] text-ink2 hover:border-accent"
          >
            복지카드 사진을 올려주세요
          </button>
        )}
        <p className="mt-2 text-[14px] leading-6 text-muted">
          등록하면 지원 대상 프로그램을 자동으로 알려드립니다.
        </p>
      </section>

      <section className="rounded-2xl border border-line px-4 py-4">
        <h2 className="mb-2.5 text-[16px] font-bold">주간 스케줄</h2>
        {care.weeklySchedule.length === 0 ? (
          <p className="text-[14px] text-muted">등록된 스케줄이 없어요.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-line">
            {care.weeklySchedule.map((s, i) => (
              <li key={s.day + i} className="flex justify-between py-2.5 text-[15px]">
                <span className="font-semibold">{s.day}</span>
                <span className="text-ink2">{s.note}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <div className="fixed bottom-0 left-1/2 w-full max-w-[430px] md:max-w-[560px] lg:max-w-[640px] -translate-x-1/2 border-t border-line bg-surface px-4 py-3">
        <button
          onClick={save}
          disabled={saving}
          className="tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:opacity-50"
        >
          {saving ? "저장 중…" : onboarding ? "저장하고 다음으로" : "저장"}
        </button>
      </div>
    </div>
  );
}
