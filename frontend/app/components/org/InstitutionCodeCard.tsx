import { useState } from "react";
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
    <div className="mb-4 flex flex-wrap items-center gap-x-5 gap-y-3 rounded border border-accent/40 bg-accentsoft p-4">
      <div className="min-w-0 flex-1">
        <p className="mb-1 text-[13px] font-semibold tracking-wider text-muted uppercase">우리 기관 코드</p>
        <p className="text-[14px] leading-6 text-ink2">
          이미 다른 기관에 다니는 아이는 새로 등록하지 말고 보호자에게 이 코드를 알려 주세요. 보호자가 연결을
          요청하면 아래에서 수락합니다.
        </p>
      </div>
      <p className="font-mono text-2xl font-bold tracking-[0.2em] text-accentink">
        {formatInviteCode(institution.code)}
      </p>
      <button
        type="button"
        onClick={copy}
        className="tap rounded bg-accent px-4 text-[15px] font-semibold text-white hover:bg-accentink"
      >
        {copied ? "복사됨" : "코드 복사"}
      </button>
    </div>
  );
}
