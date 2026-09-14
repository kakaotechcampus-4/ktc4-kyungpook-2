import { Accessibility, Building2, Home } from "lucide-react";
import type { InstitutionType } from "@/lib/types";

const ICONS: Record<InstitutionType, typeof Home> = {
  school: Home,
  center: Building2,
  assistant: Accessibility,
};

/** 기관 유형별 아이콘 사각형 — 학부모 앱 프로토타입의 기관 카드 왼쪽 아이콘을 재현한다. */
export function InstitutionIcon({ type }: { type: InstitutionType }) {
  const Icon = ICONS[type];
  return (
    <span
      aria-hidden
      className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-surface2 text-ink2"
    >
      <Icon size={20} strokeWidth={1.75} />
    </span>
  );
}
