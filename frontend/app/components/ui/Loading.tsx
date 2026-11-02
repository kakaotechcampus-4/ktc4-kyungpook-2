/**
 * 서비스 공통 로딩 — 기관 · 보호자 화면이 같은 스피너 하나, 같은 진행 막대 하나를 쓴다 (멘토 리뷰 10/31).
 * 시안: https://claude.ai/artifact/DcJUa9y14gKNddcYTi5YC2 (서비스 공통 로딩 부품)
 *
 * - Spinner        버튼 · 글 안의 "…중" 표시. 글자 앞에 붙인다
 * - Busy           버튼 글자 "…중" = 스피너 + 글자
 * - NavigationProgress  화면을 옮기는 동안 맨 위에 흐르는 막대 (root 에 하나)
 * - useNavigating  화면을 옮기는 중인지. 셸이 본문을 살짝 흐리게 하는 데 쓴다
 * - FullPageLoading  처음 열 때(HydrateFallback) 화면 가운데
 */
import { useEffect, useState } from "react";
import { LoaderCircle } from "lucide-react";
import { useNavigation } from "react-router";

/** 돌아가는 원. 색은 글자색을 따른다(currentColor) — 놓인 자리의 글자색과 같게 보인다 */
export function Spinner({ size = 16, className = "" }: { size?: number; className?: string }) {
  return (
    <LoaderCircle aria-hidden size={size} strokeWidth={2.2} className={`shrink-0 animate-spin ${className}`} />
  );
}

/**
 * 누른 버튼의 "…중" 글자 — 앞에 스피너를 붙인다. 버튼 클래스는 그대로 두고 글자 자리만 바꾼다.
 *
 *   {pending ? <Busy>발급 중…</Busy> : "새 코드 발급"}
 */
export function Busy({ children }: { children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2">
      <Spinner size={16} />
      {children}
    </span>
  );
}

/**
 * 이만큼 지나도 아직 옮기는 중일 때만 표시한다. 금방 끝나는 이동에서 막대가 깜빡이지 않게 한다.
 * mock 빌드처럼 바로 끝나는 이동에서는 아무것도 보이지 않는다.
 */
const SHOW_AFTER_MS = 150;

/**
 * 화면을 옮기는 중인지 (링크 · navigate · form 제출로 다음 화면의 clientLoader 를 기다리는 동안).
 * 같은 화면의 revalidate() 는 여기에 들어가지 않는다 — 그건 화면마다 자기 자리에서 보여 준다.
 */
export function useNavigating(): boolean {
  const busy = useNavigation().state !== "idle";
  const [shown, setShown] = useState(false);
  useEffect(() => {
    if (!busy) {
      setShown(false);
      return;
    }
    const timer = setTimeout(() => setShown(true), SHOW_AFTER_MS);
    return () => clearTimeout(timer);
  }, [busy]);
  return shown;
}

/** 맨 위 3px 막대. root 에 하나만 둔다 */
export function NavigationProgress() {
  const shown = useNavigating();
  if (!shown) return null;
  return (
    <div
      role="progressbar"
      aria-label="화면을 불러오는 중"
      className="fixed inset-x-0 top-0 z-50 h-[3px] overflow-hidden bg-accentsoft"
    >
      <div className="h-full w-2/5 animate-nav-bar bg-accent" />
    </div>
  );
}

/** 처음 열 때 — 잇다 로고를 도는 원이 감싼다 */
export function FullPageLoading() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3.5">
      <span className="relative flex size-14 items-center justify-center">
        <span className="flex size-10 items-center justify-center rounded-md bg-accent text-[18px] font-bold text-white">
          잇
        </span>
        <svg
          aria-hidden
          viewBox="0 0 56 56"
          fill="none"
          strokeWidth={2.4}
          strokeLinecap="round"
          className="absolute inset-0 size-14 animate-spin stroke-accent"
        >
          <path d="M53 28a25 25 0 0 0-25-25" />
        </svg>
      </span>
      <span role="status" className="text-[15px] text-muted">
        불러오는 중…
      </span>
    </div>
  );
}
