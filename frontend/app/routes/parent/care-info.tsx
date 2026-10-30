import { useState } from "react";
import { useLoaderData, useNavigate } from "react-router";
import { getParentChildren, getParentHome, isReady, updateChildCare } from "@/lib/api";
import { ApiError } from "@/lib/apiError";
import { ParentNotReadyPage } from "@/components/parent/NotReady";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { readSelectedChildId } from "@/lib/selectedChild";
import type { ChildCareInfo } from "@/lib/types";

const EMPTY_CARE: ChildCareInfo = {
  allergies: [],
  medications: [],
};

const COMMON_ALLERGIES = ["우유와 유제품", "땅콩", "계란", "갑각류"];

/** 서버와 같은 상한 (G-13). 넘기지 않게 입력에서 먼저 막는다 */
const MAX_ITEMS = 20;
const MAX_LENGTH = 30;

export async function clientLoader() {
  // 서버에 아직 없다. mock 을 불러 진짜 아이 옆에 띄우지 않는다 — 화면은 "준비 중"
  if (!isReady("careInfo")) return null;
  const kids = await getParentChildren();
  const { child } = await getParentHome(readSelectedChildId(kids));
  return { childId: child.id, childName: child.name, care: child.care ?? EMPTY_CARE };
}

/** 실연동 빌드에서 서버에 아직 없으면 mock 대신 "준비 중"을 보인다 (lib/api.ts BE_READY) */
export default function CareInfoPageRoute() {
  if (!isReady("careInfo")) return <ParentNotReadyPage title="아이 상세정보" back />;
  return <CareInfoPage />;
}

/**
 * 설정 › 아이 정보에서 들어오는 돌봄 정보 — 알레르기와 복용약 (G-13).
 * 현재 이 아이와 연결된 모든 보호자가 보고 고칠 수 있고, 기관에는 공유하지 않는다. 기관에 가지 않는 정보라
 * 온보딩에서는 받지 않는다. 저장은 통째로 덮어쓴다.
 */
function CareInfoPage() {
  const { childId, childName, care: initial } = useLoaderData<typeof clientLoader>()!;
  const [care, setCare] = useState<ChildCareInfo>(initial);
  const [allergyDraft, setAllergyDraft] = useState("");
  const [medName, setMedName] = useState("");
  const [medTime, setMedTime] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  const allergiesFull = care.allergies.length >= MAX_ITEMS;
  const medicationsFull = care.medications.length >= MAX_ITEMS;

  const toggleCommonAllergy = (label: string) =>
    setCare((c) =>
      c.allergies.includes(label)
        ? { ...c, allergies: c.allergies.filter((a) => a !== label) }
        : c.allergies.length >= MAX_ITEMS
          ? c
          : { ...c, allergies: [...c.allergies, label] },
    );

  const addAllergy = () => {
    const value = allergyDraft.trim();
    if (!value || allergiesFull) return;
    // 서버도 같은 값을 하나로 합친다. 화면에서 먼저 합쳐 칩이 두 번 보이지 않게 한다
    setCare((c) => (c.allergies.includes(value) ? c : { ...c, allergies: [...c.allergies, value] }));
    setAllergyDraft("");
  };
  const removeAllergy = (i: number) =>
    setCare((c) => ({ ...c, allergies: c.allergies.filter((_, idx) => idx !== i) }));

  const addMedication = () => {
    if (!medName.trim() || medicationsFull) return;
    // 복용 시간은 비워도 된다 — 빈 값으로 보낸다
    setCare((c) => ({
      ...c,
      medications: [...c.medications, { name: medName.trim(), time: medTime.trim() }],
    }));
    setMedName("");
    setMedTime("");
  };
  const removeMedication = (i: number) =>
    setCare((c) => ({ ...c, medications: c.medications.filter((_, idx) => idx !== i) }));

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await updateChildCare(childId, care);
      navigate("/parent/settings");
    } catch (err) {
      setError(saveErrorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <ParentPageHeader title="아이 상세정보" subtitle={childName} back />
      <p className="-mt-2 text-[14px] leading-6 text-muted">
        이 아이와 연결된 보호자만 보고 고칠 수 있어요. 기관에는 공유되지 않아요.
      </p>

      <section className="rounded-2xl border border-line px-4 py-4">
        <h2 className="mb-2.5 text-[16px] font-bold">식단과 알레르기</h2>
        <div className="mb-3 flex flex-wrap gap-2">
          {COMMON_ALLERGIES.map((label) => {
            const on = care.allergies.includes(label);
            return (
              <button
                key={label}
                type="button"
                onClick={() => toggleCommonAllergy(label)}
                disabled={!on && allergiesFull}
                className={`tap rounded-2xl px-4 text-[14px] font-semibold transition-colors disabled:opacity-50 ${
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

        <label htmlFor="allergy-draft" className="mb-1.5 block text-[14px] font-semibold text-ink2">
          직접 입력
        </label>
        <div className="flex gap-2">
          <input
            id="allergy-draft"
            value={allergyDraft}
            onChange={(e) => setAllergyDraft(e.target.value)}
            maxLength={MAX_LENGTH}
            placeholder="예: 갑각류(새우)"
            className="tap min-w-0 flex-1 rounded-2xl border border-line2 px-4 text-[15px] outline-none focus:border-accent"
          />
          <button
            onClick={addAllergy}
            disabled={allergiesFull}
            type="button"
            className="tap shrink-0 rounded-2xl border border-line2 px-4 text-[14px] font-semibold text-ink2 hover:bg-surface2 disabled:opacity-50"
          >
            추가
          </button>
        </div>
        {allergiesFull ? (
          <p className="mt-2 text-[13px] text-muted">알레르기는 {MAX_ITEMS}개까지 적을 수 있어요.</p>
        ) : null}
      </section>

      <section className="rounded-2xl border border-line px-4 py-4">
        <h2 className="mb-2.5 text-[16px] font-bold">지병과 복용약</h2>
        {care.medications.length > 0 ? (
          <ul className="mb-3 flex flex-col gap-2">
            {care.medications.map((m, i) => (
              <li
                key={m.name + i}
                className="flex items-center justify-between gap-2 rounded-2xl bg-surface2 px-4 py-3 text-[15px]"
              >
                <span className="min-w-0">
                  <span className="font-semibold">{m.name}</span>
                  {m.time ? <span className="text-muted"> · {m.time}</span> : null}
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
            id="medication-name"
            value={medName}
            onChange={(e) => setMedName(e.target.value)}
            maxLength={MAX_LENGTH}
            aria-label="약 이름"
            placeholder="약 이름"
            className="tap min-w-0 rounded-2xl border border-line2 px-4 text-[15px] outline-none focus:border-accent"
          />
          <input
            id="medication-time"
            value={medTime}
            onChange={(e) => setMedTime(e.target.value)}
            maxLength={MAX_LENGTH}
            aria-label="복용 시간"
            placeholder="복용 시간 (예: 점심 식후)"
            className="tap min-w-0 rounded-2xl border border-line2 px-4 text-[15px] outline-none focus:border-accent"
          />
        </div>
        <button
          onClick={addMedication}
          disabled={medicationsFull}
          type="button"
          className="tap mt-2 w-full rounded-2xl border border-dashed border-line2 px-4 text-[14px] font-semibold text-accentink hover:border-accent disabled:opacity-50"
        >
          + 약 추가
        </button>
        {medicationsFull ? (
          <p className="mt-2 text-[13px] text-muted">복용약은 {MAX_ITEMS}개까지 적을 수 있어요.</p>
        ) : null}
      </section>

      {/* 화면 아래 탭과 겹치지 않게 고정하지 않고 목록 끝에 둔다 */}
      <div className="flex flex-col gap-2">
        {error ? (
          <p role="alert" className="text-[14px] leading-6 text-block">
            {error}
          </p>
        ) : null}
        <button
          onClick={save}
          disabled={saving}
          className="tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink disabled:opacity-50"
        >
          {saving ? "저장 중…" : "저장"}
        </button>
      </div>
    </div>
  );
}

function saveErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return "네트워크 연결을 확인하고 다시 시도해 주세요.";
  if (err.status === 400) {
    return `입력한 내용을 확인해 주세요. 항목은 ${MAX_ITEMS}개, 글자는 ${MAX_LENGTH}자까지예요.`;
  }
  if (err.status === 403) return "이 아이의 정보를 고칠 수 없어요. 연결 상태를 확인해 주세요.";
  return "저장하지 못했어요. 잠시 후 다시 시도해 주세요.";
}
