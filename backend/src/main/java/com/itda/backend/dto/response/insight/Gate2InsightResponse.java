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
 * @param teacherWritten 근거(대처 근거 포함)에 교사가 Gate 1 에서 검증 없이 고치거나 새로 쓴 문장이 있는지.
 *                      {@code true} 면 화면이 "교사 작성 포함" 경고를 띄운다 (CRITERIA §5, #323)
 * @param primarySource 기관 종류 우선순위(학교 &gt; 센터 &gt; 활동지원사)로 뽑은 승인 주체. 이 기관만 승인한다
 * @param evidence      근거가 된 승인 요약들
 * @param targets       승인하면 이 글을 받을 후보 기관과 동의 상태. <b>동의가 granted 인 기관만 실제로 받는다</b>
 *                      (#233). 그 아이 명부에 있는 기관 중 승인 기관 자신만 뺀 것이다
 * @param gate2Status   pending · approved · hold
 */
public record Gate2InsightResponse(
        String id,
        String childId,
        String childName,
        String period,
        String content,
        boolean needsReview,
        boolean teacherWritten,
        InstitutionResponse primarySource,
        List<EvidenceRef> evidence,
        List<Target> targets,
        String gate2Status) {

    /**
     * 근거가 된 승인 요약 한 건. {@code label} 은 화면 문구라 고정값이다.
     *
     * @param lookedUp 이번 기간이 아니라 AI 가 지난 기록에서 찾아 쓴 근거인지 (#387). 하나라도 {@code true} 면 화면이
     *                 "지난 기록을 찾아 씀" 을 표시하고, 이 근거를 날짜·기관과 함께 "지난 기록" 으로 구분한다
     */
    public record EvidenceRef(String childContextId, String date, String label, InstitutionResponse institution,
            boolean lookedUp) {
    }

    public record Target(InstitutionResponse institution, ConsentState consent) {
    }
}
