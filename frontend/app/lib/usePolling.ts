import { useEffect, useRef } from "react";

export function usePolling(tick: () => Promise<unknown> | void, intervalMs = 10_000, enabled = true, immediate = true) {
  const tickRef = useRef(tick);
  tickRef.current = tick;

  useEffect(() => {
    if (!enabled) return;
    let timer: ReturnType<typeof setInterval> | null = null;
    let running = false;

    async function run() {
      if (running) return;
      running = true;
      try {
        await tickRef.current();
      } catch {
      } finally {
        running = false;
      }
    }
    function start(now = true) {
      if (timer) return;
      if (now) void run();
      timer = setInterval(run, intervalMs);
    }
    function stop() {
      if (timer) clearInterval(timer);
      timer = null;
    }
    function onVisibility() {
      if (document.visibilityState === "hidden") stop();
      else start();
    }

    if (document.visibilityState !== "hidden") start(immediate);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [intervalMs, enabled, immediate]);
}
