package com.itda.backend.dto.response.insight;

import java.util.List;

import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * Gate 2 검토 목록·단건의 인사이트 한 편 (api-spec O-40 · O-41).
 *
 * @param period        화면에 그대로 쓰는 "2026년 8월 3주차" 같은 문구
 * @param needsReview   공유 전에 꼭 봐야 하는 글인지. 본문에 <b>다른 아이 이름이 없다는 보장이 없고</b>,
 *                      Gate 1 에서 교사가 검증 없이 고치거나 새로 쓴 문장이 근거로 섞일 수 있다
 *                      ({@code AI/insight/CRITERIA.md} §5). 기관 경계를 넘는 마지막 관문이라 표시한다
 * @param primarySource 기관 종류 우선순위(학교 &gt; 센터 &gt; 활동지원사)로 뽑은 승인 주체. 이 기관만 승인한다
 * @param evidence      근거가 된 승인 요약들
 * @param targets       이 아이가 다니는 다른 기관과 동의 상태. <b>읽기 전용 참고다</b> — 수신 기관은
 *                      기관이 고르지 않고 승인 뒤 보호자가 정한다 (#206)
 * @param gate2Status   pending · approved · hold
 */
public record Gate2InsightResponse(
        String id,
        String childId,
        String childName,
        String period,
        String content,
        boolean needsReview,
        InstitutionResponse primarySource,
        List<EvidenceRef> evidence,
        List<Target> targets,
        String gate2Status) {

    /** 근거가 된 승인 요약 한 건. {@code label} 은 화면 문구라 고정값이다. */
    public record EvidenceRef(String childContextId, String date, String label, InstitutionResponse institution) {
    }

    public record Target(InstitutionResponse institution, ConsentState consent) {
    }
}
