import type { StageState } from "@/components/ui";
import type { EntryProgress } from "@/lib/types";

/**
 * 기록 등록 → 요약 검토 대기까지 자동 진행되는 파이프라인 단계 — 대시보드/업로드 화면이 공유한다.
 * 라벨은 사이드바 메뉴명(기록 등록)·상세 페이지 제목(요약 검토)과 통일했다. 이름은 내부 도메인(매칭 · 검증 · 요약)과 같게 쓴다 (멘토 리뷰 10/31).
 */
export const PIPELINE_STAGES = ["기록 등록", "매칭", "검증", "요약", "요약 검토 대기"] as const;

export const MATCHING_INDEX = 1;
export const VALIDATION_INDEX = 2;

/** 사람 승인이 필요해 자동 진행이 멈추는 단계 인덱스 */
export const GATE1_INDEX = PIPELINE_STAGES.length - 1;

/**
 * 기록 등록 화면의 파일별 진행 단계. 파일은 아이가 정해질 때(매칭)까지만 본다 —
 * 그 뒤 검증 · 요약 · 요약 검토는 아이 × 날짜 단위라 오늘의 업무 묶음 카드(O-34)가 보여준다.
 */
export const FILE_STAGES = ["기록 등록", "기록 나누기", "매칭"] as const;

/** 세부 단계 동그라미 아래 짧은 이름 — 좁은 칸이라 "기록"을 한 번만 쓴다 (#359) */
export const FILE_STAGE_SHORT = ["기록 등록", "나누기", "매칭"] as const;

/**
 * 검증에서 막혀 수정 요청 큐에 있는 기록. 아이는 정해졌지만 선생님이 고치거나 보류해야 넘어간다 —
 * 서버는 검증 단계의 "사람 대기"로 내려준다(VALIDATION_BLOCKED, O-26). 파일 진행에서 "완료"로 세지 않는다 (#359).
 */
export const isBlocked = (e: EntryProgress) => e.stageIndex === VALIDATION_INDEX && e.state === "waiting";

/** 매칭까지 온 기록. 이보다 뒤(stageIndex 2 이상)는 아이별 카드로 넘어갔다 */
export const isBeforeChild = (e: EntryProgress) => e.stageIndex <= MATCHING_INDEX;

/**
 * 파일 하나를 FILE_STAGES 상태로 접는다.
 *
 * 매칭 단계는 아직 아이가 안 정해진 기록만 본다. 그중 멈춘 건이 하나라도 있으면 멈춘 상태로
 * 보여야 한다 — 다른 건이 넘어갔다고 초록 체크로 덮으면 안 된다.
 * 우선순위: 실패 > 사람 대기 > 진행 중 > 완료.
 */
export function fileStageStatesOf(entries: EntryProgress[]): StageState[] {
  // 기록을 아직 떼어내지 못했다
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

/**
 * 파일 하나의 진행률 — 기록 등록 화면의 막대 하나(멘토 리뷰 10/31, 시안 https://claude.ai/artifact/DcJUa9y14gKNddcYTi5YC2).
 *
 * 막대는 FILE_STAGES 세 칸을 ⅓씩 나눠 가진다. 기록 등록 · 기록 나누기는 끝나면 한 칸씩 차고,
 * 마지막 매칭 칸은 아이가 정해진(또는 우리 기관 아이가 아니라서 뺀) 기록 수만큼 찬다.
 * 기록을 아직 떼어내지 못했으면 entries 가 비어 있어 ⅓에서 기다린다.
 *
 * tone 은 막대 색과 상태 줄을 고른다. 우선순위는 fileStageStatesOf 와 같다: 실패 > 사람 대기 > 진행 중 > 완료.
 * 다만 검증에서 막힌 기록(수정 요청)도 사람 대기로 친다 — 세부 단계는 매칭까지라 그 기록을 보지 않는다 (#359).
 */
export type FileTone = "splitting" | "running" | "waiting" | "failed" | "done";

export interface FileProgressSummary {
  /** 0 ~ 100 */
  percent: number;
  tone: FileTone;
  total: number;
  /** 매칭을 마친 기록 (아이가 정해져 넘어갔거나, 우리 기관 아이가 아니라 뺐다) */
  resolved: number;
  /** 매칭 중 */
  running: number;
  /** 매칭 확인에서 사람이 골라야 한다 */
  waiting: number;
  /** 매칭에서 시스템 오류 */
  failed: number;
  /** 검증에서 막혀 수정 요청 큐에서 고치거나 보류해야 한다 */
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
  // 막힌 기록은 아이는 정해졌지만 선생님 손이 남았다 — 다 끝난 100% 로 보이지 않게 막대에서만 뺀다.
  // 세부 단계(fileStageStatesOf)는 매칭까지라 그대로 완료다. 상태 칩과 펼친 줄이 수정 요청으로 보낸다
  const blocked = entries.filter(isBlocked).length;
  const tone: FileTone =
    failed > 0 ? "failed" : waiting > 0 || blocked > 0 ? "waiting" : running > 0 ? "running" : "done";
  const percent = Math.round(((2 + (resolved - blocked) / total) / 3) * 100);
  return { percent, tone, total, resolved, running, waiting, failed, blocked };
}
