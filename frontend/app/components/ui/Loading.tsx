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

/** 한 번에 치는 글자 수 · 치는 간격. 사람이 또박또박 치는 속도다 */
const TYPE_STEP = 2;
const TYPE_TICK = 85;

const MESSAGE = "아이의 세상을 연결하는 중";

/**
 * 처음 열 때(root 의 HydrateFallback).
 *
 * 그림을 쓰지 않는다 — 로고 한 장뿐이다. 클레이 그림을 네모에 앉히면 그림마다
 * 여백이 달라 미묘하게 어긋나 보이고, 보호자는 SMS 링크로 모바일에서 들어와
 * **첫 페인트가 동의 완료율을 좌우한다.**
 *
 * 세 가지가 움직인다.
 *   로고   통통 뛴다
 *   글     두 글자씩 쳐진다 — 멈춘 화면이 아니라는 걸 글이 말한다
 *   막대   빠르게 차다가 느려진다
 *
 * ⚠️ 막대는 **진짜 진행률이 아니다.** 얼마나 걸릴지 알 수 없어서, 끝에서 멈추지 않고
 * 97% 까지 천천히 기어간다 (NProgress 가 쓰는 꼴). 100% 로 채워 두면 다 됐다고
 * 거짓말하는 화면이 된다.
 */
export function FullPageLoading() {
  return (
    <div
      className="flex min-h-screen flex-col items-center justify-center gap-9 px-6"
      /* 기관 · 보호자 캔버스와 같은 두 겹 바탕. 이 화면은 셸 **밖**이라
         org.css · parent.css 가 닿지 않아 여기에 직접 깐다 */
      style={{
        background:
          "radial-gradient(620px 380px at 100% 0%, #fff0b4 0%, rgb(255 240 180 / 0) 64%)," +
          "radial-gradient(560px 460px at 0% 100%, #ddf2e3 0%, rgb(221 242 227 / 0) 64%)," +
          "var(--color-paper)",
      }}
    >
      <img
        src="/img/logo.png"
        alt="잇다"
        width={600}
        height={220}
        className="block h-auto w-[180px] animate-logo-bob"
      />

      <Typing />

      <div
        role="progressbar"
        aria-label="서비스를 준비하는 중"
        className="h-2.5 w-full max-w-[248px] overflow-hidden rounded-full bg-line"
      >
        <div className="h-full origin-left animate-load-fill rounded-full bg-accent" />
      </div>
    </div>
  );
}

/**
 * 두 글자씩 쳐지는 글.
 *
 * 글이 길어지면 가운데 맞춤이 매 글자마다 흔들린다. 그래서 **다 친 글을 안 보이게 깔아**
 * 자리를 미리 잡고, 그 위에 쳐지는 만큼만 그린다. 글은 왼쪽에서 오른쪽으로 자란다.
 *
 * 읽어 주는 프로그램에는 다 친 글 한 번만 들려준다 — 두 글자마다 다시 읽으면 못 듣는다.
 */
function Typing() {
  const [typed, setTyped] = useState(0);

  useEffect(() => {
    if (typed >= MESSAGE.length) return;
    const timer = setTimeout(
      () => setTyped((n) => Math.min(n + TYPE_STEP, MESSAGE.length)),
      TYPE_TICK,
    );
    return () => clearTimeout(timer);
  }, [typed]);

  return (
    <p className="grid text-[17px] leading-none font-semibold tracking-[-0.02em] text-ink2">
      <span role="status" className="sr-only">
        {MESSAGE}
      </span>
      {/* 자리만 잡는 글. 안 보이지만 너비를 차지한다 */}
      <span aria-hidden className="invisible col-start-1 row-start-1">
        {MESSAGE}…
      </span>
      <span aria-hidden className="col-start-1 row-start-1">
        {MESSAGE.slice(0, typed)}
        {typed >= MESSAGE.length ? (
          <span className="animate-dots">…</span>
        ) : (
          <span className="animate-caret text-accent">|</span>
        )}
      </span>
    </p>
  );
}
