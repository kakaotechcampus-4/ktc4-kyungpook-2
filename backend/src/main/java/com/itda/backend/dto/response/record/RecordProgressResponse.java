package com.itda.backend.dto.response.record;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 업로드한 파일 한 개의 처리 현황 (api-spec O-26). 프론트 I-03·I-05 가 쓴다.
 *
 * <p>파일을 기록 단위로 나누는 작업이 끝나기 전까지는 {@code entries} 가 비어 있다. 화면은 "기록 등록 중"
 * 에 머무는데 당분간 그게 정상이다 (PDF 는 아직 쪼개지지 않는다).
 *
 * @param entries 이 파일에서 나온 일지들. 등록 순서대로
 */
public record RecordProgressResponse(
        String rawRecordId,
        String fileName,
        LocalDateTime uploadedAt,
        List<EntryProgress> entries) {

    /**
     * 일지 한 건이 어디까지 갔는지.
     *
     * @param stageIndex 프론트 {@code PIPELINE_STAGES} 순서 — 0 등록 · 1 매칭 · 2 검증 · 3 요약 · 4 1차 검토
     * @param state      {@code running} 자동 진행 중 · {@code waiting} 사람 확인 대기 ·
     *                   {@code failed} 시스템 오류 · {@code done} 완료
     */
    public record EntryProgress(String id, int stageIndex, String state) {
    }
}
