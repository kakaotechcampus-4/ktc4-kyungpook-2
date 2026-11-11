import { CircleHelp } from "lucide-react";
import type { InstitutionType } from "@/lib/types";

const PICTURE: Record<InstitutionType, string> = {
  school: "/img/school.png",
  center: "/img/center.png",
  assistant: "/img/adult-01.png",
};

export function InstitutionIcon({ type }: { type?: InstitutionType | null }) {
  if (!type) {
    return (
      <span
        aria-hidden
        className="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-surface2 text-ink2"
      >
        <CircleHelp size={20} strokeWidth={1.75} />
      </span>
    );
  }
  return (
    <img
      src={PICTURE[type]}
      alt=""
      aria-hidden
      width={44}
      height={44}
      decoding="async"
      className="size-11 shrink-0 object-contain"
    />
  );
}
