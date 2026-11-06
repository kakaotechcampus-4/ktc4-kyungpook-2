import { useEffect, useRef } from "react";

/**
 * 화면이 보이는 동안 `intervalMs` 마다 `tick` 을 부른다. 처음 붙을 때 한 번 바로 부른다.
 * 탭이 숨으면 멈추고, 다시 보이면 바로 한 번 부른 뒤 이어 간다.
 * 앞 호출이 끝나기 전에는 다음 호출을 겹쳐 보내지 않는다.
 */
export function usePolling(tick: () => Promise<unknown> | void, intervalMs = 10_000, enabled = true) {
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
        // 폴링 실패는 다음 차례에 다시 시도한다. 화면별 오류 표시는 tick 안에서 한다
      } finally {
        running = false;
      }
    }
    function start() {
      if (timer) return;
      void run();
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

    if (document.visibilityState !== "hidden") start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [intervalMs, enabled]);
}
