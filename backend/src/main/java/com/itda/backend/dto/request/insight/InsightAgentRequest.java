package com.itda.backend.dto.request.insight;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 인사이트 에이전트 {@code POST /insight} 요청. 필드는 {@code AI/insight/schemas.py} 의 InsightInput 과 1:1 이다.
 *
 * <p>앞의 에이전트들과 재료가 다르다 — 일지가 아니라 <b>Gate 1 을 통과한 요약의 문장(claim)</b> 을 보낸다.
 * 여러 날·여러 기관의 claim 을 모아 "상황 → 반응 → 대처와 결과" 묶음을 찾는 게 이 에이전트의 일이다.
 *
 * @param periodFrom             재료가 된 기간 시작 (ISO, 예: "2026-10-01")
 * @param consentedInstitutions  보호자가 공유에 동의한 기관. 이 밖의 기관 claim 은 재료에서 뺀다
 *                               (AI/insight/CRITERIA.md §7). {@code sharing_consent} 가 ACTIVE 인 기관이어야 한다
 *                               (DB 스키마 §5.5). 지금은 {@code InsightService} 가 아이가 다니는 기관 전부를 넣는다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InsightAgentRequest(
        Long childId,
        String childName,
        String periodFrom,
        String periodTo,
        List<Claim> claims,
        List<Institution> consentedInstitutions) {

    /**
     * 목록은 비어 있더라도 {@code null} 로 보내지 않는다.
     *
     * <p>AI 쪽 기본값({@code Field(default_factory=list)})은 <b>키가 없을 때만</b> 쓰인다. 값이 명시적으로
     * {@code null} 이면 {@code list[Institution]} 검증에 걸려 422 가 되고, 422 는 계약 위반이라 재시도 없이
     * 바로 실패한다. 조회 결과가 없는 경우가 실제 경로다 — 동의가 모두 철회된 아이면 동의 기관 조회가 비고,
     * 그 결과가 {@code null} 로 흘러들어올 수 있다.
     */
    public InsightAgentRequest {
        claims = claims == null ? List.of() : claims;
        consentedInstitutions = consentedInstitutions == null ? List.of() : consentedInstitutions;
    }

    /**
     * 승인된 요약 문장 하나 (InsightClaim).
     *
     * @param claimId         <b>BE 가 붙인다.</b> 요약에 없는 값이라 {@code "{summary_result.id}-{claims 배열 순서}"}
     *                        로 만든다. 요약이 배열 순서를 본문 순서로 못 박았으므로 같은 판 안에서는 바뀌지 않는다
     * @param institutionType <b>한국어다.</b> {@code "학교"}·{@code "센터"}·{@code "활동지원사"}
     *                        (AI/insight/config.py INSTITUTION_TYPES). 이 문자열이 프롬프트에 그대로 들어가고
     *                        기관 간 대조도 이 값으로 한다. 기관 <b>이름</b>은 보내지 않는다
     * @param evidence        <b>최소 1 건.</b> 근거가 끊긴 문장은 보내지 않는다 — 원문으로 추적되지 않는 내용이
     *                        다른 기관으로 나가면 안 된다 (CRITERIA §2)
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Claim(
            String claimId,
            String text,
            String entryDate,
            Long institutionId,
            String institutionType,
            List<Evidence> evidence) {
    }

    /**
     * claim 하나를 뒷받침하는 원본 일지 근거 (InsightEvidence).
     *
     * @param quote ⚠️ <b>원문 인용이라 다른 아이 이름이 남아 있다.</b> claim 의 {@code text} 는 익명화됐지만
     *              이것은 아니다. AI 는 이 값을 프롬프트에 넣지 않고 출력으로도 옮기지 않는다 (CRITERIA §2)
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Evidence(Long journalEntryId, String quote) {
    }

    /** 동의된 기관 하나. 이름은 보내지 않는다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Institution(Long institutionId, String institutionType) {
    }
}
