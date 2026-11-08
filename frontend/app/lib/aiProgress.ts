import { useCallback, useEffect, useRef, useState } from "react";
import { getAiProgress } from "@/lib/api";
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

const POLL_MS = 1_000;

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

/** 붙잡아 둔 문구. 받았을 때의 줄 상태가 그대로인 동안만 쓴다 */
interface Held extends AiPhase {
  status: string;
}

/**
 * AI 차례 줄이 있고 AI 가 멀쩡한 동안 1초마다 진행 정보를 받아, 줄마다 보일 문구를 돌려준다.
 * - 서버가 꺼 두었거나(enabled: false) 요청이 실패하면(404 · 5xx) 이 화면에 있는 동안 더 묻지 않는다 → 기존 문구
 * - AI 가 일을 끝내 진행 정보에서 줄이 빠져도, 줄 상태가 바뀔 때까지 마지막 문구를 붙잡아 둔다(고정 문구로 되돌아가 보이지 않게).
 *   빠진 줄이 보이면 진행 현황(refresh)을 바로 한 번 다시 받는다 — 10초 폴링을 기다리지 않게
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
  /** 지난 응답에 있던 열쇠 — 이번에 빠진 줄을 찾는 데 쓴다 */
  const lastKeys = useRef<Set<string>>(new Set());

  const hasAi = rows.some((r) => r.who === "ai");

  async function tick() {
    let res;
    try {
      res = await getAiProgress();
    } catch {
      res = null;
    }
    if (!res?.enabled) {
      setOn(false);
      setHeld(new Map());
      lastKeys.current = new Set();
      return;
    }
    const aiRows = new Map(rowsRef.current.filter((r) => r.who === "ai").map((r) => [rowKeyOf(r), r]));
    const fresh = new Map<string, Held>();
    const keys = new Set<string>();
    for (const i of res.items) {
      const key = progressKey(i.kind, i.childId, i.kind === "week" ? i.weekFrom : i.date);
      keys.add(key);
      const row = aiRows.get(key);
      const text = phaseText(i.agent, i.node);
      if (row && text) fresh.set(key, { text, sec: Math.max(0, Math.floor(i.elapsedSec)), status: row.status });
    }
    const gone = [...lastKeys.current].some((k) => !keys.has(k));
    lastKeys.current = keys;
    if (fresh.size) setHeld((prev) => new Map([...prev, ...fresh]));
    if (gone) void refreshRef.current().catch(() => {});
  }
  usePolling(tick, POLL_MS, on && !aiDown && hasAi, true);

  // 줄 상태가 바뀌었거나 줄이 사라졌으면 붙잡아 둔 문구를 버린다
  useEffect(() => {
    setHeld((prev) => {
      if (!prev.size) return prev;
      const status = new Map(rows.filter((r) => r.who === "ai").map((r) => [rowKeyOf(r), r.status as string]));
      const next = new Map([...prev].filter(([k, h]) => status.get(k) === h.status));
      return next.size === prev.size ? prev : next;
    });
  }, [rows]);

  // AI 점검 중에는 점검 문구가 우선이다. 돌아오면 새로 받는다
  useEffect(() => {
    if (!aiDown) return;
    setHeld(new Map());
    lastKeys.current = new Set();
  }, [aiDown]);

  return useCallback(
    (r: PipelineRow) => {
      if (r.who !== "ai") return null;
      const h = held.get(rowKeyOf(r));
      return h && h.status === r.status ? { text: h.text, sec: h.sec } : null;
    },
    [held],
  );
}
