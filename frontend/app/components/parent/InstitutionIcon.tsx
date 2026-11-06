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
      /*
        lazy 로 두지 않는다. 이 그림은 홈 첫 화면의 **접히는 선 위**에 나온다
        ("새 소식" 카드마다 하나). 늦게 뜨면 보호자가 처음 보는 화면에 빈 회색
        네모가 줄줄이 남는다 — 디자인이 안 먹은 것처럼 보인다.
        종류가 셋뿐이라(school · center · adult-01) 한 번 받으면 끝이다.
      */
      decoding="async"
      /* 바탕도 모서리도 주지 않는다 — 그림이 이미 둥근 네모를 가지고 있다
         (frontend/public/img/README.md). 씌우면 네모 안에 네모가 생긴다. */
      className="size-11 shrink-0 object-contain"
    />
  );
}
