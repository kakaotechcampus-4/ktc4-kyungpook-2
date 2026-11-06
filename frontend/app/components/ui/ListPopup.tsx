import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

/**
 * 목록에서 한 건을 눌렀을 때 뜨는 처리 팝업. 머리 · 스크롤 본문 · 버튼 줄로 나뉜다.
 * 바깥을 누르거나 Esc 로 닫힌다. 좁은 화면(520px 이하)에서는 아래에서 올라오는 시트가 된다.
 * `position` 을 주면 머리에 "n / N" 을 보인다(같은 종류 다음 건이 이어서 열릴 때).
 */
export function ListPopup({
  title,
  subtitle,
  icon,
  position,
  busy = false,
  onClose,
  footer,
  children,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  icon?: React.ReactNode;
  position?: { index: number; total: number };
  /** 처리 중에는 닫히지 않는다 */
  busy?: boolean;
  onClose: () => void;
  footer?: React.ReactNode;
  children: React.ReactNode;
}) {
  const titleId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== "Escape" || busy) return;
      // 위에 확인 창이 떠 있으면 Esc 는 그 창만 닫는다 — 팝업까지 닫으면 고치던 내용이 사라진다
      if (document.querySelectorAll('[role="dialog"][aria-modal]').length > 1) return;
      closeRef.current();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy]);

  // 팝업이 떠 있는 동안 뒤 화면이 스크롤되지 않게 한다
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  return (
    <div
      className="fixed inset-0 z-50 grid animate-pop-fade place-items-center bg-ink/40 px-4 py-6 max-[520px]:place-items-end max-[520px]:px-0 max-[520px]:pb-0 motion-reduce:animate-none"
      onClick={(e) => {
        if (e.target === e.currentTarget && !busy) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="flex max-h-full w-full max-w-[760px] animate-pop-up flex-col overflow-hidden rounded-[22px] bg-surface shadow-[0_24px_60px_rgb(39_36_31/0.28)] max-[520px]:max-h-[92%] max-[520px]:rounded-b-none motion-reduce:animate-none"
      >
        <header className="flex items-center gap-3 border-b border-line px-5 pb-3.5 pt-[18px]">
          {icon ? (
            <span className="grid size-[42px] flex-none place-items-center rounded-full bg-accentsoft text-accentink">
              {icon}
            </span>
          ) : null}
          <div className="min-w-0 flex-1">
            <h2 id={titleId} className="text-[18px] font-extrabold tracking-[-0.02em] text-balance">
              {title}
            </h2>
            {subtitle ? <p className="mt-0.5 text-[13px] text-muted">{subtitle}</p> : null}
          </div>
          {position && position.total > 1 ? (
            <span className="flex-none text-[13px] font-bold tabular-nums text-muted">
              {position.index} / {position.total}
            </span>
          ) : null}
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            aria-label="닫기"
            className="grid size-9 flex-none place-items-center rounded-full text-[18px] text-muted hover:bg-surface2 hover:text-ink disabled:opacity-50"
          >
            ✕
          </button>
        </header>
        <div
          tabIndex={0}
          className="grid min-h-0 flex-auto content-start gap-[18px] overflow-y-auto overscroll-contain px-[22px] pb-[22px] pt-[18px] focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent"
        >
          {children}
        </div>
        {footer ? (
          <footer className="flex flex-wrap items-center gap-2 border-t border-line bg-surface px-5 py-3.5 max-[520px]:pb-[calc(14px+env(safe-area-inset-bottom,0px))]">
            {footer}
          </footer>
        ) : null}
      </div>
    </div>
  );
}

/**
 * 목록 팝업으로 한 건씩 이어서 처리하기.
 * `open(id)` 로 열고, 처리가 끝나면 `done(id)` — 그 건은 ✓ 표시 뒤 2초 지나 목록에서 빠지고,
 * 팝업은 아직 처리하지 않은 다음 건(없으면 앞 건)으로 넘어가며, 남은 건이 없으면 닫힌다.
 * `list` 는 서버에서 받은 목록 그대로 준다. 처리한 건이 폴링으로 먼저 빠져도 그대로 동작한다.
 * `skip` 이 참인 건(예: 요약을 다시 만드는 중)은 목록에는 보이되 "다음 건" 과 "n / N" 에서 뺀다.
 */
export function useWalk<T>(list: T[], idOf: (item: T) => string, skip?: (item: T) => boolean) {
  const [openId, setOpenId] = useState<string | null>(null);
  /** ✓ 를 보이는 중인 건 */
  const [doneIds, setDoneIds] = useState<ReadonlySet<string>>(new Set());
  /** 2초가 지나 목록에서 숨긴 건 (서버 목록에서 빠질 때까지) */
  const [goneIds, setGoneIds] = useState<ReadonlySet<string>>(new Set());
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  // 서버 목록에서 빠진 건은 숨김 목록에서도 지운다 — 같은 id 가 나중에 다시 오면(보류를 풀어 돌아옴) 보여야 한다
  useEffect(() => {
    setGoneIds((g) => {
      if (g.size === 0) return g;
      const live = new Set(list.map(idOf));
      const next = new Set([...g].filter((id) => live.has(id)));
      return next.size === g.size ? g : next;
    });
  }, [list, idOf]);

  const visible = useMemo(() => list.filter((it) => !goneIds.has(idOf(it))), [list, goneIds, idOf]);
  /** 팝업이 돌 수 있는 건 = 아직 처리하지 않은 것 */
  const todo = useMemo(
    () => visible.filter((it) => !doneIds.has(idOf(it)) && !skip?.(it)),
    [visible, doneIds, idOf, skip],
  );

  const current = openId == null ? null : (visible.find((it) => idOf(it) === openId) ?? null);
  const index = current ? todo.findIndex((it) => idOf(it) === openId) : -1;

  const done = useCallback(
    (id: string) => {
      const at = todo.findIndex((it) => idOf(it) === id);
      const rest = todo.filter((it) => idOf(it) !== id);
      const next = rest.length === 0 ? null : rest[Math.min(Math.max(at, 0), rest.length - 1)];
      setDoneIds((s) => new Set(s).add(id));
      timers.current.push(
        setTimeout(() => {
          setGoneIds((s) => new Set(s).add(id));
          setDoneIds((s) => {
            const n = new Set(s);
            n.delete(id);
            return n;
          });
        }, 2000),
      );
      setOpenId((cur) => (cur === id ? (next ? idOf(next) : null) : cur));
    },
    [todo, idOf],
  );

  return {
    /** 목록에 그릴 건 (✓ 표시 중인 것 포함) */
    visible,
    isDone: (id: string) => doneIds.has(id),
    current,
    /** 머리의 "n / N". 열린 게 없으면 undefined */
    position: current && index >= 0 ? { index: index + 1, total: todo.length } : undefined,
    open: setOpenId,
    close: () => setOpenId(null),
    done,
  };
}
