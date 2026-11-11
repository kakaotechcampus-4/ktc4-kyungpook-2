import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

export type TourScreen = "dash" | "check" | "review" | "kids" | "lists" | "home";

interface Step {
  at: string | null;
  title: string;
  text: string;
}

const TOURS: Record<TourScreen, Step[]> = {
  dash: [
    { at: "aiday", title: "오늘 AI가 한 일", text: "선생님이 확인하는 동안 AI가 스스로 처리한 일이에요. 선생님은 AI가 정하지 못한 것과 승인만 하면 돼요." },
    { at: "turn", title: "선생님 차례", text: "선생님이 눌러야 다음 단계로 넘어가는 일이 모두 여기 있어요. 할 일이 생기면 건수와 [바로가기]가 붙어요." },
    { at: "upload", title: "기록 올리기", text: "관찰일지 파일을 여기에 끌어다 놓으세요. AI가 날짜별로 기록을 나누고 누구의 기록인지 찾아요." },
    { at: "pipe", title: "6단계 진행 현황", text: "관찰일지 한 건이 보호자에게 가기까지 거치는 단계예요. 단계를 누르면 그 단계에 있는 기록만 아래에 보여요." },
    { at: "table", title: "일지별 진행", text: "일지마다 지금 몇 단계인지 동그라미로 보여요. 노란 동그라미는 선생님 차례, 초록 동그라미는 AI가 하는 중이에요." },
    { at: "menu", title: "메뉴", text: "일을 종류별로 따로 보고 싶을 때는 위 메뉴를 쓰세요. 숫자는 선생님이 처리할 건수예요." },
  ],
  check: [
    { at: "confirm", title: "아이 확인", text: "AI가 누구의 기록인지 정하지 못한 일지예요. 일지에 이름이 없거나 비슷한 이름이 여럿일 때 여기로 와요." },
    { at: "blocked", title: "고쳐야 할 기록", text: "개인정보나 진단명이 있어 내용 검증에서 막힌 기록이에요. 고쳐서 다시 보내거나, 쓰지 않을 기록이면 빼요." },
    { at: null, title: "팝업에서 처리", text: "카드의 버튼을 누르면 팝업이 열려요. 하나를 처리하면 다음 건이 이어서 떠요." },
  ],
  review: [
    { at: "list", title: "검토할 요약", text: "AI가 그날 기록을 요약한 거예요. 보호자에게 가기 전에 선생님이 읽고 승인해야 해요. 요약에 실패한 날도 여기 카드로 와요." },
    {
      at: null,
      title: "검토하기",
      text: "팝업에서 문장마다 유지·수정·삭제를 고르세요. 문장 옆 [근거]를 누르면 원본 일지에서 그 문장의 근거가 노랗게 보여요. 승인한 뒤 5초 동안은 되돌릴 수 있어요.",
    },
    { at: null, title: "승인한 뒤에는", text: "승인한 기록은 한 주 단위로 모여 AI가 주간 리포트를 써요. 리포트가 오면 아동 목록에서 한 번 더 승인해요." },
  ],
  kids: [
    { at: "tabs", title: "세 칸으로 나눠 봐요", text: "아이: 우리 명부의 아이 카드 · 리포트 승인: AI가 쓴 주간 리포트를 읽고 승인 · 연결 요청: 보호자가 기관 코드로 보낸 요청" },
    { at: "orgcode", title: "우리 기관 코드", text: "이미 다른 기관에 다니는 아이는 새로 등록하지 말고 보호자에게 이 코드를 알려 주세요. 보호자가 연결을 요청하면 [연결 요청]에 떠요. 초대 코드와 달리 만료가 없어요." },
    { at: "register", title: "아이 등록", text: "새 아이를 등록하면 보호자 초대 코드가 나와요. 보호자가 코드로 연결하고 공유에 동의하면 기록을 받을 수 있어요." },
    { at: "kids", title: "아이 팝업", text: "[열기]를 누르면 리포트·기록·보호자 탭이 있는 팝업이 떠요. 다른 기관이 보낸 리포트는 [새 리포트] 배지로 알려 주고, 지난 리포트는 주차별로 묶여 있어요." },
  ],
  lists: [
    { at: "composer", title: "새로 보내기", text: "준비물이나 확인할 일을 보호자에게 보내요." },
    { at: "sent", title: "확인 현황", text: "보호자가 몇 명 확인했는지 보여요. 필요 없어진 안내는 [내리기]로 내려요." },
  ],
  home: [
    { at: "news", title: "새 소식", text: "기관에서 올린 그날의 기록이에요. 눌러서 전체를 읽을 수 있어요." },
    { at: "orgs", title: "연결된 기관", text: "아이가 다니는 곳이에요. 기관마다 기록을 공유할지 보호자가 정해요." },
    { at: "tabs", title: "아래 메뉴", text: "타임라인은 날짜별 기록, 리포트는 여러 기관 기록을 묶어 정리한 주간 글이에요. 설정에서 공유를 켜고 꺼요." },
  ],
};

const seenKey = (screen: TourScreen) => `itda.tour.${screen}.seen`;

function target(at: string | null): HTMLElement | null {
  return at ? document.querySelector<HTMLElement>(`[data-tour="${at}"]`) : null;
}

export function TourButton({ screen, auto = false }: { screen: TourScreen; auto?: boolean }) {
  const [steps, setSteps] = useState<Step[] | null>(null);
  const button = useRef<HTMLButtonElement>(null);

  const start = useCallback(() => {
    const live = TOURS[screen].filter((s) => s.at == null || target(s.at));
    if (live.length) setSteps(live);
  }, [screen]);

  useEffect(() => {
    if (!auto) return;
    let seen = true;
    try {
      seen = localStorage.getItem(seenKey(screen)) === "1";
    } catch {
    }
    if (!seen) start();
  }, [auto, start]);

  function end() {
    if (auto) {
      try {
        localStorage.setItem(seenKey(screen), "1");
      } catch {
      }
    }
    setSteps(null);
    button.current?.focus({ preventScroll: true });
  }

  return (
    <>
      <button
        ref={button}
        type="button"
        onClick={start}
        aria-label="이 화면 사용법 보기"
        title="사용법"
        className="inline-grid size-[26px] flex-none place-items-center rounded-full border-[1.5px] border-line2 bg-surface text-[14px] font-extrabold text-muted hover:border-ink2 hover:text-ink"
      >
        ?
      </button>
      {steps ? createPortal(<Coach steps={steps} onEnd={end} />, document.body) : null}
    </>
  );
}

function Coach({ steps, onEnd }: { steps: Step[]; onEnd: () => void }) {
  const [i, setI] = useState(0);
  const step = steps[i];
  const last = i === steps.length - 1;
  const bub = useRef<HTMLDivElement>(null);
  const next = useRef<HTMLButtonElement>(null);
  const [spot, setSpot] = useState<{ left: number; top: number; width: number; height: number } | null>(null);
  const [pos, setPos] = useState<{ left: number; top: number; width: number } | null>(null);

  const place = useCallback(() => {
    const el = target(step.at);
    if (!el) {
      setSpot(null);
      setPos(null);
      return;
    }
    const r = el.getBoundingClientRect();
    const pad = 8;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    setSpot({ left: r.left - pad, top: r.top - pad, width: r.width + pad * 2, height: r.height + pad * 2 });
    const bw = Math.min(340, vw - 32);
    const bh = bub.current?.offsetHeight ?? 180;
    const below = r.bottom + pad + 12;
    const above = r.top - pad - 12 - bh;
    const top = below + bh <= vh - 16 ? below : above >= 16 ? above : Math.max(16, vh - bh - 16);
    const left = Math.min(Math.max(16, r.left + r.width / 2 - bw / 2), vw - bw - 16);
    setPos({ left, top, width: bw });
  }, [step]);

  useLayoutEffect(() => {
    target(step.at)?.scrollIntoView({ block: "center" });
    place();
    next.current?.focus({ preventScroll: true });
  }, [step, place]);

  useEffect(() => {
    window.addEventListener("scroll", place, { passive: true });
    window.addEventListener("resize", place);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onEnd();
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("scroll", place);
      window.removeEventListener("resize", place);
      window.removeEventListener("keydown", onKey);
    };
  }, [place, onEnd]);

  return (
    <div className="fixed inset-0 z-[90]">
      <div className={`absolute inset-0 ${spot ? "" : "bg-[rgb(20_18_15/0.62)]"}`} />
      {spot ? (
        <div
          aria-hidden
          className="fixed rounded-[18px] shadow-[0_0_0_9999px_rgb(20_18_15/0.62),0_0_0_3px_var(--color-mark)] transition-all duration-200 motion-reduce:transition-none"
          style={spot}
        />
      ) : null}
      <div
        ref={bub}
        role="dialog"
        aria-modal="true"
        aria-labelledby="tour-t"
        aria-describedby="tour-d"
        className={`fixed z-[2] rounded-[18px] bg-surface px-[18px] pt-4 pb-3.5 text-ink shadow-[0_18px_44px_rgb(20_18_15/0.32)] ${
          pos ? "" : "top-1/2 left-1/2 w-[min(340px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2"
        }`}
        style={pos ?? undefined}
      >
        <div className="mb-1.5 flex items-center justify-between">
          <span className="text-[12px] font-extrabold tabular-nums text-accentink">
            {i + 1} / {steps.length}
          </span>
          <button type="button" onClick={onEnd} className="px-0.5 py-1 text-[13px] font-bold text-muted hover:text-ink hover:underline">
            건너뛰기
          </button>
        </div>
        <h3 id="tour-t" className="mb-1 text-[17px] font-extrabold tracking-[-0.02em]">
          {step.title}
        </h3>
        <p id="tour-d" className="text-[14.5px] leading-[1.6] text-ink2">
          {step.text}
        </p>
        <div className="mt-3 flex items-center justify-between gap-2">
          <span aria-hidden className="flex gap-[5px]">
            {steps.map((s, k) => (
              <i key={s.title} className={`h-1.5 rounded-full ${k === i ? "w-[18px] bg-ink" : "w-1.5 bg-line2"}`} />
            ))}
          </span>
          <span className="flex gap-1.5">
            {i ? (
              <button type="button" onClick={() => setI(i - 1)} className="tap rounded-full border border-line2 px-3.5 text-[14px] font-bold text-ink2">
                이전
              </button>
            ) : null}
            <button
              ref={next}
              type="button"
              onClick={() => (last ? onEnd() : setI(i + 1))}
              className="tap rounded-full bg-ink px-4 text-[14px] font-bold text-paper"
            >
              {last ? "다 봤어요" : "다음"}
            </button>
          </span>
        </div>
      </div>
    </div>
  );
}
