import { useState } from "react";
import { HelpTip } from "@/components/ui/HelpTip";
import { formatInviteCode } from "@/lib/invite";
import type { MyInstitution } from "@/lib/types";

/**
 * 우리 기관 코드 — 이미 다른 기관에 다니는 아이의 보호자에게 알려 준다(I-09).
 * 보호자가 이 코드로 연결을 요청하면 아래 "연결 요청" 영역에 뜬다. 초대 코드와 달리 고정이라 만료가 없다.
 */
export function InstitutionCodeCard({ institution }: { institution: MyInstitution }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(formatInviteCode(institution.code));
      setCopied(true);
    } catch {
      // 클립보드 권한이 없으면 코드를 직접 읽어 전달하면 된다 — 화면에 그대로 보인다.
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-4 rounded-[22px] border border-accent/25 bg-accentsoft p-6">
      <div className="flex min-w-0 flex-1 items-center gap-1">
        <p className="text-[17px] font-extrabold text-accentink">우리 기관 코드</p>
        <HelpTip label="우리 기관 코드" align="left">
          이미 다른 기관에 다니는 아이는 새로 등록하지 말고 보호자에게 이 코드를 알려 주세요.
          보호자가 연결을 요청하면 아래 "연결 요청"에서 수락합니다. 초대 코드와 달리 만료가 없습니다.
        </HelpTip>
      </div>
      <p className="text-[26px] font-extrabold tracking-[0.18em] text-accentink tabular-nums">
        {formatInviteCode(institution.code)}
      </p>
      <button
        type="button"
        onClick={copy}
        className="tap rounded-full bg-ink px-5 text-[15px] font-bold text-paper hover:brightness-110"
      >
        {copied ? "복사됨" : "코드 복사"}
      </button>
    </div>
  );
}
