import { CircleHelp } from "lucide-react";
import type { InstitutionType } from "@/lib/types";

/**
 * 기관 유형 그림. 선생님 화면(`upload-progress`)과 같은 그림을 쓴다.
 *
 * 사람이 아니라 **유형**을 가리킨다 — 어느 학교인지, 어느 센터인지는 구분하지 않는다.
 * 그래서 아이 얼굴(`ChildFace`)과 달리 같은 그림이 여러 기관에 겹쳐도 된다.
 */
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
      loading="lazy"
      className="size-11 shrink-0 rounded-2xl bg-surface2 object-contain p-0.5"
    />
  );
}
