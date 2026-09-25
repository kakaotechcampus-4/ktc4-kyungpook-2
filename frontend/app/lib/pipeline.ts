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
 * 한 파일에서 나온 기록 여러 건을 단계별 상태 하나로 접는다.
 *
 * 건마다 도달한 단계가 다르다. 어떤 단계에 멈춘 건이 하나라도 있으면 그 단계는
 * 멈춘 상태로 보여야 한다 — 다른 건이 지나갔다고 초록 체크로 덮으면 안 된다.
 * 우선순위: 실패 > 사람 대기 > 진행 중 > 완료(누군가 지나감) > 대기 전.
 */
export function stageStatesOf(entries: EntryProgress[]): StageState[] {
  return PIPELINE_STAGES.map((_, i) => {
    const here = entries.filter((e) => e.stageIndex === i);
    if (here.some((e) => e.state === "failed")) return "failed";
    if (here.some((e) => e.state === "waiting")) return "waiting";
    if (here.some((e) => e.state === "running")) return "active";
    // 기록을 아직 떼어내지 못한 파일은 등록 단계에 머물러 있다
    if (entries.length === 0) return i === 0 ? "active" : "pending";
    // 지금 여기 머문 건은 없고 지나간 건이 있다 — 뒤처진 건은 앞 단계에서 이미 표시된다
    return entries.some((e) => e.stageIndex > i) ? "done" : "pending";
  });
}
