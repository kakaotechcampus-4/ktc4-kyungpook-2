import type { StageState } from "@/components/ui";
import type { EntryProgress } from "@/lib/types";

/**
 * 기록 등록 → 1차 검토 대기까지 자동 진행되는 파이프라인 단계 — 대시보드/업로드 화면이 공유한다.
 * 라벨은 사이드바 메뉴명(기록 등록)·상세 페이지 제목(1차 검토 · 요약 확인)과 통일했다.
 */
export const PIPELINE_STAGES = ["기록 등록", "매칭", "검증", "요약", "1차 검토 대기"] as const;

export const MATCHING_INDEX = 1;
export const VALIDATION_INDEX = 2;

/** 사람 승인이 필요해 자동 진행이 멈추는 단계 인덱스 */
export const GATE1_INDEX = PIPELINE_STAGES.length - 1;

/**
 * 기록 등록 화면의 파일별 진행 단계. 파일은 아이가 정해질 때(매칭)까지만 본다 —
 * 그 뒤 검증 · 요약 · 1차 검토는 아이 × 날짜 단위라 오늘의 업무 묶음 카드(O-34)가 보여준다.
 */
export const FILE_STAGES = ["기록 등록", "기록 나누기", "아이 찾기"] as const;

/** 아이 찾기(매칭)까지 온 기록. 이보다 뒤(stageIndex 2 이상)는 아이별 카드로 넘어갔다 */
export const isBeforeChild = (e: EntryProgress) => e.stageIndex <= MATCHING_INDEX;

/**
 * 파일 하나를 FILE_STAGES 상태로 접는다.
 *
 * 아이 찾기는 아직 아이가 안 정해진 기록만 본다. 그중 멈춘 건이 하나라도 있으면 멈춘 상태로
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
