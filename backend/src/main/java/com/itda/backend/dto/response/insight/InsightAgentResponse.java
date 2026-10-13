package com.itda.backend.dto.response.insight;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 인사이트 에이전트 응답. 필드는 {@code AI/insight/schemas.py} 의 InsightOutput 과 1:1 이다.
 *
 * <p><b>한 번 호출이 인사이트 여러 건을 돌려준다.</b> {@code insight_result} 는 한 행이 {@link Insight} 하나라
 * 한 번 생성이 여러 행이 된다 (docs/db/schema.md §9.3).
 *
 * <p><b>{@code insights} 가 비어도 200 이고, 그것도 정답이다.</b> 억지로 만든 패턴이 과잉해석이다
 * (AI/insight/CRITERIA.md §3). 모델을 못 써서 못 낸 경우는 200 이 아니라 503 으로 온다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InsightAgentResponse(
        Long childId,
        String periodFrom,
        String periodTo,
        List<Insight> insights,
        Boolean llmCalled) {

    /**
     * 인사이트 한 건.
     *
     * <p>{@code supports}·{@code claimIds}·{@code sourceEntryIds}·{@code sourceInstitutionIds} 는 AI 가 보낸
     * JSON 조각 그대로 두고 {@code insight_result} 의 TEXT 컬럼에 그대로 저장한다 — {@code summary_result.claims}
     * 와 같은 방식이다.
     *
     * @param situation             어떤 상황에서
     * @param behavior              어떤 반응이 나타나는지
     * @param content               교사·보호자가 읽을 본문.
     *                              ⚠️ <b>다른 아이 이름이 없다는 보장이 없다</b> — 요약 익명화와 프롬프트로만
     *                              막고 보장하는 층이 없다 (CRITERIA §5). Gate 2 화면이 그 사실을 표시해야 한다
     * @param sourceInstitutionIds  <b>근거를 낸</b> 기관이지 받을 기관이 아니다. 수신 후보는 BE 가 Gate 2 시점의
     *                              동의로 계산한다 — AI 가 수신 기관을 추천하던 방식은 #137 에서 되돌렸다
     * @param institutionTypes      근거 기관의 종류. {@code insight_result} 에 담지 않는다 —
     *                              {@code sourceInstitutionIds} 로 organization 을 조인하면 나오는 값이라
     *                              복사해 두면 두 벌이 어긋난다 (§9.3)
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Insight(
            String situation,
            String behavior,
            String content,
            JsonNode supports,
            JsonNode claimIds,
            JsonNode sourceEntryIds,
            JsonNode sourceInstitutionIds,
            JsonNode institutionTypes,
            Boolean crossInstitution) {
    }
}
