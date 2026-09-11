import { useState, useTransition } from "react";
import { useNavigate } from "react-router";
import { addInstitutionByCode, lookupInstitutionByCode } from "@/lib/api";
import { INSTITUTION_SHARE_FIELDS } from "@/lib/mock/data";
import { InstitutionChip } from "@/components/ui";
import { InstitutionIcon } from "@/components/parent/InstitutionIcon";
import { ParentPageHeader } from "@/components/parent/ParentPageHeader";
import { useChildContext } from "@/components/parent/ChildContext";
import type { Institution } from "@/lib/types";

/** "기관 직접 추가" — 초대코드 없이 보호자가 기관 코드를 입력해 연결한다. */
export default function InstitutionAddPage() {
  const { selected } = useChildContext();
  const [code, setCode] = useState("");
  const [found, setFound] = useState<Institution | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [pending, start] = useTransition();
  const navigate = useNavigate();

  async function lookup(e: React.FormEvent) {
    e.preventDefault();
    const institution = await lookupInstitutionByCode(code);
    if (institution) {
      setFound(institution);
      setNotFound(false);
    } else {
      setNotFound(true);
    }
  }

  if (found) {
    const { shared, notShared } = INSTITUTION_SHARE_FIELDS[found.type];
    return (
      <div className="flex flex-col gap-5">
        <ParentPageHeader title={`${found.name}에 권한을 줄까요`} back />
        <div className="flex items-center gap-3 rounded-2xl border border-line px-4 py-3">
          <InstitutionIcon type={found.type} />
          <InstitutionChip institution={found} withName />
        </div>
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
        <div className="flex gap-2">
          <button
            onClick={() => navigate("/parent/settings")}
            className="tap h-14 flex-1 rounded-2xl border border-line2 text-[16px] font-bold text-ink2"
          >
            나중에
          </button>
          <button
            disabled={pending}
            onClick={() =>
              start(async () => {
                await addInstitutionByCode(selected.id, code);
                navigate("/parent/settings");
              })
            }
            className="tap h-14 flex-1 rounded-2xl bg-accent text-[16px] font-bold text-white hover:bg-accentink disabled:opacity-50"
          >
            {pending ? "연결 중…" : "확인 후 권한 부여"}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5">
      <ParentPageHeader title="기관 직접 추가" back />
      <p className="text-[15px] leading-7 text-ink2">기관 코드를 입력하면 바로 연결됩니다.</p>
      <form onSubmit={lookup} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1.5">
          <span className="text-[15px] font-semibold">기관 코드</span>
          <input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="ITDA-INST-0000"
            required
            className="tap w-full rounded-2xl border border-line2 px-4 font-mono text-[16px] tracking-widest outline-none focus:border-accent"
          />
        </label>
        {notFound ? (
          <p className="rounded-2xl border border-block/40 bg-blocksoft px-4 py-3 text-[14px] text-block">
            일치하는 기관을 찾지 못했어요. 코드를 다시 확인해주세요.
          </p>
        ) : null}
        <button className="tap h-14 w-full rounded-2xl bg-accent px-4 text-[16px] font-bold text-white hover:bg-accentink">
          다음
        </button>
      </form>
    </div>
  );
}
