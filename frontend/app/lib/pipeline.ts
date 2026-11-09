import type { StageState } from "@/components/ui";
import type { EntryProgress } from "@/lib/types";

export const PIPELINE_STAGES = ["기록 등록", "매칭", "검증", "요약", "요약 검토 대기"] as const;

export const MATCHING_INDEX = 1;
export const VALIDATION_INDEX = 2;

export const GATE1_INDEX = PIPELINE_STAGES.length - 1;

export const FILE_STAGES = ["기록 등록", "기록 나누기", "매칭"] as const;

export const FILE_STAGE_SHORT = ["기록 등록", "나누기", "매칭"] as const;

export const isBlocked = (e: EntryProgress) => e.stageIndex === VALIDATION_INDEX && e.state === "waiting";

export const isBeforeChild = (e: EntryProgress) => e.stageIndex <= MATCHING_INDEX;

export function fileStageStatesOf(entries: EntryProgress[]): StageState[] {
  if (entries.length === 0) return ["done", "active", "pending"];
  const early = entries.filter(isBeforeChild);
  const matching: StageState = early.some((e) => e.state === "failed")
    ? "failed"
    : early.some((e) => e.state === "waiting")
      ? "waiting"
      : early.some((e) => e.state === "running")
        ? "active"
        : "done";
  return ["done", "done", matching];
}

export type FileTone = "splitting" | "running" | "waiting" | "failed" | "done";

export interface FileProgressSummary {
  percent: number;
  tone: FileTone;
  total: number;
  resolved: number;
  running: number;
  waiting: number;
  failed: number;
  blocked: number;
}

export function fileProgressOf(entries: EntryProgress[]): FileProgressSummary {
  const total = entries.length;
  if (total === 0) {
    return { percent: 33, tone: "splitting", total, resolved: 0, running: 0, waiting: 0, failed: 0, blocked: 0 };
  }
  const early = entries.filter(isBeforeChild);
  const running = early.filter((e) => e.state === "running").length;
  const waiting = early.filter((e) => e.state === "waiting").length;
  const failed = early.filter((e) => e.state === "failed").length;
  const resolved = total - running - waiting - failed;
  const blocked = entries.filter(isBlocked).length;
  const tone: FileTone =
    failed > 0 ? "failed" : waiting > 0 || blocked > 0 ? "waiting" : running > 0 ? "running" : "done";
  const percent = Math.round(((2 + (resolved - blocked) / total) / 3) * 100);
  return { percent, tone, total, resolved, running, waiting, failed, blocked };
}
