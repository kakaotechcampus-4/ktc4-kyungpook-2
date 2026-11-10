import { useEffect, useState } from "react";
import { LoaderCircle } from "lucide-react";
import { useNavigation } from "react-router";

export function Spinner({ size = 16, className = "" }: { size?: number; className?: string }) {
  return (
    <LoaderCircle aria-hidden size={size} strokeWidth={2.2} className={`shrink-0 animate-spin ${className}`} />
  );
}

export function Busy({ children }: { children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2">
      <Spinner size={16} />
      {children}
    </span>
  );
}

const SHOW_AFTER_MS = 150;

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

const TYPE_STEP = 2;
const TYPE_TICK = 85;

const MESSAGE = "아이의 세상을 연결하는 중";

export function FullPageLoading() {
  return (
    <div
      className="flex min-h-screen flex-col items-center justify-center gap-9 px-6"
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
