import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, getAiProgress } from "@/lib/api";
import type { PipelineRow } from "@/lib/types";
import { usePolling } from "@/lib/usePolling";

const PHASE: Record<string, Record<string, string>> = {
  matching: {
    extract: "일지에서 아이 이름을 찾고 있어요",
    shortlist: "아동 명부와 맞춰 보고 있어요",
    llm_judge: "헷갈리는 이름을 한 번 더 살펴보고 있어요",
    decide: "누구의 기록인지 정하고 있어요",
  },
  validation: {
    perceive: "전화번호 같은 개인정보가 있는지 보고 있어요",
    plan: "전화번호 같은 개인정보가 있는지 보고 있어요",
    act: "진단명이나 다른 아이 이름이 있는지 살피고 있어요",
    reflect: "확인 결과를 정리하고 있어요",
  },
  summary: {
    gather: "그날 일지를 모으고 있어요",
    write: "요약 문장을 쓰고 있어요",
    ground: "요약이 원문과 맞는지 대조하고 있어요",
    assemble: "요약을 마무리하고 있어요",
  },
  insight: {
    perceive: "공유를 허락한 기관의 기록만 고르고 있어요",
    plan: "한 주 기록에서 되풀이되는 행동을 찾고 있어요",
    act: "한 주 기록에서 되풀이되는 행동을 찾고 있어요",
    reflect: "찾은 내용이 기록에 근거하는지 확인하고 있어요",
    refine: "근거가 약한 문장을 다시 쓰고 있어요",
    find_claims: "지난 기록을 찾아보고 있어요",
  },
};

const AGENT_STEPS: Record<string, readonly number[]> = { matching: [0], validation: [1], summary: [2, 3], insight: [4] };

const POLL_MS = 1_000;
const HOLD_MS = 15_000;
const REFRESH_GAP_MS = 3_000;
const MAX_FAILS = 3;
const PAUSE_MS = 5_000;

export function phaseText(agent: string, node: string): string | null {
  const nodes = Object.hasOwn(PHASE, agent) ? PHASE[agent] : null;
  return nodes && Object.hasOwn(nodes, node) ? nodes[node] : null;
}

function progressKey(kind: string, childId: string | null, day: string | null): string {
  return `${kind}|${childId ?? ""}|${day ?? ""}`;
}

function rowKeyOf(r: PipelineRow): string {
  return progressKey(r.kind, r.childId, r.kind === "week" ? (r.week?.from ?? null) : r.date);
}

export interface AiPhase {
  text: string;
  sec: number;
}

interface Held extends AiPhase {
  status: string;
}

function sameHeld(a: Map<string, Held>, b: Map<string, Held>): boolean {
  if (a.size !== b.size) return false;
  for (const [k, h] of a) {
    const o = b.get(k);
    if (!o || o.text !== h.text || o.sec !== h.sec || o.status !== h.status) return false;
  }
  return true;
}

export function useAiProgress(
  rows: PipelineRow[],
  aiDown: boolean,
  refresh: () => Promise<void>,
): (r: PipelineRow) => AiPhase | null {
  const [on, setOn] = useState(true);
  const [held, setHeld] = useState<Map<string, Held>>(() => new Map());
  const rowsRef = useRef(rows);
  rowsRef.current = rows;
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  const lastSeen = useRef<Set<string>>(new Set());
  const seenAt = useRef<Map<string, number>>(new Map());
  const fails = useRef(0);
  const pausedUntil = useRef(0);
  const lastRefreshAt = useRef(0);
  const refreshDue = useRef(false);

  const active = on && !aiDown && rows.some((r) => r.who === "ai");
  const activeRef = useRef(active);
  activeRef.current = active;

  function forget() {
    setHeld((prev) => (prev.size ? new Map() : prev));
    lastSeen.current = new Set();
    seenAt.current = new Map();
    refreshDue.current = false;
  }

  async function tick() {
    if (Date.now() < pausedUntil.current) return;
    let res;
    try {
      res = await getAiProgress();
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setOn(false);
        forget();
      } else if (++fails.current >= MAX_FAILS) {
        fails.current = 0;
        pausedUntil.current = Date.now() + PAUSE_MS;
        forget();
      }
      return;
    }
    fails.current = 0;
    if (!res.enabled) {
      setOn(false);
      forget();
      return;
    }
    if (!activeRef.current) return;

    const now = Date.now();
    const aiRows = new Map(rowsRef.current.filter((r) => r.who === "ai").map((r) => [rowKeyOf(r), r]));
    const fresh = new Map<string, Held>();
    const seen = new Set<string>();
    for (const i of res.items) {
      const key = progressKey(i.kind, i.childId, i.kind === "week" ? i.weekFrom : i.date);
      seen.add(`${key}|${i.agent}`);
      seenAt.current.set(key, now);
      const row = aiRows.get(key);
      const text = phaseText(i.agent, i.node);
      if (row && text && AGENT_STEPS[i.agent]?.includes(row.step)) {
        fresh.set(key, { text, sec: Math.max(0, Math.floor(i.elapsedSec)), status: row.status });
      }
    }
    for (const [k, t] of seenAt.current) if (now - t > HOLD_MS) seenAt.current.delete(k);
    if ([...lastSeen.current].some((k) => !seen.has(k))) refreshDue.current = true;
    lastSeen.current = seen;
    setHeld((prev) => {
      const next = new Map([...prev].filter(([k]) => seenAt.current.has(k)));
      for (const [k, h] of fresh) next.set(k, h);
      return sameHeld(prev, next) ? prev : next;
    });
    if (refreshDue.current && now - lastRefreshAt.current >= REFRESH_GAP_MS) {
      refreshDue.current = false;
      lastRefreshAt.current = now;
      void refreshRef.current().catch(() => {});
    }
  }
  usePolling(tick, POLL_MS, active, true);

  useEffect(() => {
    if (!active) forget();
  }, [active]);

  return useCallback(
    (r: PipelineRow) => {
      if (r.who !== "ai") return null;
      const h = held.get(rowKeyOf(r));
      return h && h.status === r.status ? { text: h.text, sec: h.sec } : null;
    },
    [held],
  );
}
