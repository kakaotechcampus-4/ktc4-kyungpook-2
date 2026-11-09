import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

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
      if (document.querySelectorAll('[role="dialog"][aria-modal]').length > 1) return;
      closeRef.current();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy]);

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

export function useWalk<T>(list: T[], idOf: (item: T) => string, skip?: (item: T) => boolean) {
  const [openId, setOpenId] = useState<string | null>(null);
  const [doneIds, setDoneIds] = useState<ReadonlySet<string>>(new Set());
  const [goneIds, setGoneIds] = useState<ReadonlySet<string>>(new Set());
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  useEffect(() => {
    setGoneIds((g) => {
      if (g.size === 0) return g;
      const live = new Set(list.map(idOf));
      const next = new Set([...g].filter((id) => live.has(id)));
      return next.size === g.size ? g : next;
    });
  }, [list, idOf]);

  const visible = useMemo(() => list.filter((it) => !goneIds.has(idOf(it))), [list, goneIds, idOf]);
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
      clearTimeout(timers.current.get(id));
      timers.current.set(
        id,
        setTimeout(() => {
          timers.current.delete(id);
          setGoneIds((s) => new Set(s).add(id));
          setDoneIds((s) => without(s, id));
        }, 2000),
      );
      setOpenId((cur) => (cur === id ? (next ? idOf(next) : null) : cur));
    },
    [todo, idOf],
  );

  const undo = useCallback((id: string) => {
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
    setDoneIds((s) => without(s, id));
    setGoneIds((s) => without(s, id));
  }, []);

  return {
    visible,
    isDone: (id: string) => doneIds.has(id),
    current,
    position: current && index >= 0 ? { index: index + 1, total: todo.length } : undefined,
    open: setOpenId,
    close: () => setOpenId(null),
    done,
    undo,
  };
}

function without(set: ReadonlySet<string>, id: string): ReadonlySet<string> {
  if (!set.has(id)) return set;
  const n = new Set(set);
  n.delete(id);
  return n;
}
