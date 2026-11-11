import { useCallback, useEffect, useRef, useState } from "react";

export interface Carried<D> {
  drafts: Record<string, D>;
  gone: string[];
}

export function splitCarried<D>(saved: Record<string, D>, liveIds: Iterable<string>): Carried<D> {
  const live = new Set(liveIds);
  return {
    drafts: Object.fromEntries(Object.entries(saved).filter(([id]) => live.has(id))),
    gone: Object.keys(saved).filter((id) => !live.has(id)),
  };
}

export function useCarried<D>(
  loaded: Carried<D>,
  forget: (ids: string[]) => void,
  readAll: () => Record<string, D>,
) {
  const [carried, setCarried] = useState(loaded);
  const [loggedOut, setLoggedOut] = useState(false);
  const ref = useRef(carried);
  ref.current = carried;

  useEffect(() => {
    setCarried((c) => {
      const gone = loaded.gone.filter((id) => id in c.drafts);
      if (gone.length === 0) return c;
      const drafts = { ...c.drafts };
      for (const id of gone) delete drafts[id];
      return { drafts, gone: [...c.gone, ...gone] };
    });
  }, [loaded.gone]);

  useEffect(() => {
    forget(carried.gone);
  }, [carried.gone, forget]);

  const used = useCallback(
    (id: string) => {
      setCarried((c) => {
        if (!(id in c.drafts)) return c;
        const drafts = { ...c.drafts };
        delete drafts[id];
        return { ...c, drafts };
      });
      forget([id]);
    },
    [forget],
  );
  const lost = useCallback(() => setLoggedOut(true), []);
  const reached = useCallback(() => {
    setLoggedOut((was) => {
      if (was) forget(Object.keys(readAll()).filter((id) => !(id in ref.current.drafts)));
      return false;
    });
  }, [forget, readAll]);

  return { carried, loggedOut, used, lost, reached };
}

export const RESTORED = "로그인 전에 고치던 내용을 되살렸어요.";
