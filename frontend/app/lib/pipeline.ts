/**
 * 기록 등록 → 1차 검토 대기까지 자동 진행되는 파이프라인 단계 — 대시보드/업로드 화면이 공유한다.
 * 라벨은 사이드바 메뉴명(기록 등록)·상세 페이지 제목(1차 검토 · 요약 확인)과 통일했다.
 */
export const PIPELINE_STAGES = ["기록 등록", "매칭", "검증", "요약", "1차 검토 대기"] as const;

/** 사람 승인이 필요해 자동 진행이 멈추는 단계 인덱스 */
export const GATE1_INDEX = PIPELINE_STAGES.length - 1;
