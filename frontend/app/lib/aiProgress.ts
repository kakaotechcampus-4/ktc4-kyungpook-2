import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, getAiProgress } from "@/lib/api";
import type { PipelineRow } from "@/lib/types";
import { usePolling } from "@/lib/usePolling";

/**
 * 진행 현황 AI 줄의 지금 단계 문구 (O-83, #454).
 * (에이전트, 노드)는 AI 그래프의 실제 노드 이름이다. 표에 없는 값이 오면 화면은 기존 고정 문구(describe)를 쓴다.
 */
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

/**
 * 에이전트가 일하는 진행 현황 단계(O-80 step). 줄은 10초마다 받아서 1초 진행 정보보다 늦을 수 있다 — 단계가 맞을 때만 붙인다.
 * 요약은 처음 만들 때(summarizing) 2, 다시 만들 때(regenerating) 3 이다
 */
const AGENT_STEPS: Record<string, readonly number[]> = { matching: [0], validation: [1], summary: [2, 3], insight: [4] };

const POLL_MS = 1_000;
/** 진행 정보에서 빠진 뒤 마지막 문구를 붙잡아 두는 최대 시간. 빠지면 진행 현황을 바로 다시 받으니 보통 1~2초면 줄 상태가 바뀐다 */
const HOLD_MS = 15_000;
/** 빠진 줄 때문에 진행 현황을 다시 받는 최소 간격 — 진행 정보가 잠깐씩 비어도 셸 요청이 몰리지 않게 */
const REFRESH_GAP_MS = 3_000;
/** 이만큼 잇달아 실패하면 PAUSE_MS 동안 묻지 않고 기존 문구를 쓴다. 한 번 끊긴 것(배포 중 502 · 와이파이)으로 끄지 않고, 돌아오면 다시 켜진다 */
const MAX_FAILS = 3;
const PAUSE_MS = 30_000;

export function phaseText(agent: string, node: string): string | null {
  const nodes = Object.hasOwn(PHASE, agent) ? PHASE[agent] : null;
  return nodes && Object.hasOwn(nodes, node) ? nodes[node] : null;
}

/** 진행 정보와 줄을 잇는 열쇠. entry 줄은 날짜, week 줄은 주 시작일로 붙는다 (매칭 줄은 childId 가 null) */
function progressKey(kind: string, childId: string | null, day: string | null): string {
  return `${kind}|${childId ?? ""}|${day ?? ""}`;
}

function rowKeyOf(r: PipelineRow): string {
  return progressKey(r.kind, r.childId, r.kind === "week" ? (r.week?.from ?? null) : r.date);
}

export interface AiPhase {
  text: string;
  /** 그 일을 시작한 뒤 지난 초 */
  sec: number;
}

/** 붙잡아 둔 문구. 받았을 때의 줄 상태가 그대로인 동안만, 진행 정보에서 빠진 뒤 HOLD_MS 까지만 쓴다 */
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

/**
 * AI 차례 줄이 있고 AI 가 멀쩡한 동안 1초마다 진행 정보를 받아, 줄마다 보일 문구를 돌려준다.
 * - 서버가 꺼 두었거나(enabled: false) 진행 API 가 없으면(404) 이 화면에 있는 동안 더 묻지 않는다 → 기존 문구.
 *   잇달아 실패하면(MAX_FAILS) PAUSE_MS 동안 쉬었다가 다시 묻는다
 * - AI 가 일을 끝내 진행 정보에서 줄이 빠져도, 줄 상태가 바뀔 때까지(최대 HOLD_MS) 마지막 문구를 붙잡아 둔다 — 고정 문구로
 *   되돌아가 보이지 않게. 빠진 줄이 보이면 진행 현황(refresh)을 바로 한 번 다시 받는다 — 10초 폴링을 기다리지 않게
 */
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
  /** 지난 응답에 있던 (열쇠, 에이전트) — 이번에 빠지거나 다음 에이전트로 넘어간 줄을 찾는 데 쓴다 */
  const lastSeen = useRef<Set<string>>(new Set());
  /** 열쇠마다 진행 정보에 마지막으로 있었던 시각(ms). 화면에 안 보이는 값이라 state 에 두지 않는다 */
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
    // 기다리는 사이 폴링이 꺼졌으면(점검 중 · AI 줄 없음) 버린다 — 비운 값을 옛 응답으로 다시 채우지 않게
    if (!activeRef.current) return;

    const now = Date.now();
    const aiRows = new Map(rowsRef.current.filter((r) => r.who === "ai").map((r) => [rowKeyOf(r), r]));
    const fresh = new Map<string, Held>();
    const seen = new Set<string>();
    for (const i of res.items) {
      const key = progressKey(i.kind, i.childId, i.kind === "week" ? i.weekFrom : i.date);
      seen.add(`${key}|${i.agent}`);
      // 열쇠가 남아 있으면(다음 에이전트로 넘어가 줄이 아직 옛 단계여도) AI 는 일하는 중이니 붙잡는 시간을 늘린다
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
      // 문구 · 초가 그대로면 다시 그리지 않는다
      return sameHeld(prev, next) ? prev : next;
    });
    // 끝났거나 다음 에이전트로 넘어간 줄이 있으면 진행 현황을 바로 다시 받는다. 간격 안이면 미뤘다가 다음 차례에
    if (refreshDue.current && now - lastRefreshAt.current >= REFRESH_GAP_MS) {
      refreshDue.current = false;
      lastRefreshAt.current = now;
      void refreshRef.current().catch(() => {});
    }
  }
  usePolling(tick, POLL_MS, active, true);

  // 폴링을 멈추면(AI 줄이 없음 · 점검 중 · 꺼짐) 붙잡은 문구와 지난 열쇠를 비운다 — 다시 켜질 때 옛 값으로 그리거나 다시 받지 않게
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
