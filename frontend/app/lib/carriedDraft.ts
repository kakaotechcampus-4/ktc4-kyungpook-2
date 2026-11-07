import { useCallback, useEffect, useRef, useState } from "react";

/**
 * 로그인이 풀린 동안 맡겨 둔 고치던 글(`gate1Draft` · `reinputDraft`)을 화면이 다루는 공통 규칙.
 *
 * - loader 가 목록을 받은 뒤 저장소를 읽어 지금 목록에 있는 칸(`drafts`)과 빠진 칸(`gone`)으로 나눠 준다.
 * - 화면은 처음 받은 `drafts` 만 붙잡는다. 팝업이 쓰면(되살렸든 버렸든) 그 칸을 빼고 저장소에서도 지운다.
 * - `gone` 은 위에 한 줄로 알리고 저장소에서 지운다.
 * - 저장이 401 로 실패하면 `lost()` — 그 뒤로 팝업이 고칠 때마다 저장소에 맡긴다.
 * - 401 뒤에 다른 요청이 서버에 닿으면 `reached()` — 다른 탭에서 다시 로그인한 것이라, 이 화면이 맡긴 칸을 지운다.
 */
export interface Carried<D> {
  drafts: Record<string, D>;
  gone: string[];
}

/** loader 용: 저장소 칸을 지금 목록(`liveIds`)에 있는 것과 빠진 것으로 나눈다. 저장소는 지우지 않는다 */
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

  // 머무는 동안 다른 곳에서 처리돼 빠진 칸도 알린다. 이미 쓴 칸은 다시 가져오지 않는다
  useEffect(() => {
    setCarried((c) => {
      const gone = loaded.gone.filter((id) => id in c.drafts);
      if (gone.length === 0) return c;
      const drafts = { ...c.drafts };
      for (const id of gone) delete drafts[id];
      return { drafts, gone: [...c.gone, ...gone] };
    });
  }, [loaded.gone]);

  // 빠진 칸은 저장소에서 지운다. 번호를 골라 지우므로 두 번 돌아도 같다
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
      // 아직 팝업에 그리지 않은 맡긴 칸은 남긴다
      if (was) forget(Object.keys(readAll()).filter((id) => !(id in ref.current.drafts)));
      return false;
    });
  }, [forget, readAll]);

  return { carried, loggedOut, used, lost, reached };
}

export const RESTORED = "로그인 전에 고치던 내용을 되살렸어요.";
