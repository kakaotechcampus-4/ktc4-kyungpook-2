import { Accessibility, Building2, CircleHelp, Home } from "lucide-react";
import type { InstitutionType } from "@/lib/types";

const ICONS: Record<InstitutionType, typeof Home> = {
  school: Home,
  center: Building2,
  assistant: Accessibility,
};

/**
 * 기관 유형별 아이콘 사각형 — 학부모 앱 프로토타입의 기관 카드 왼쪽 아이콘을 재현한다.
 * 기관을 모르면(받은 리포트의 승인 기관 행이 사라진 경우) 물음표 원을 그린다.
 */
export function InstitutionIcon({ type }: { type?: InstitutionType | null }) {
  const Icon = type ? ICONS[type] : CircleHelp;
  return (
    <span
      aria-hidden
      className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-surface2 text-ink2"
    >
      <Icon size={20} strokeWidth={1.75} />
    </span>
  );
}
