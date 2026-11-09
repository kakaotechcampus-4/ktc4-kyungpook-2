package com.itda.backend.service.insight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gate 2 를 승인할 기관을 고르는 규칙 (#206). 목록 조회와 결정이 같은 답을 봐야 해서 한 곳에 둔다 —
 * 둘이 갈리면 목록에 보이는데 승인이 403 나거나, 그 반대가 된다.
 *
 * <pre>
 * 학교 &gt; 센터 &gt; 활동지원사        같은 종류가 둘이면 organization.id 가 작은 쪽
 * </pre>
 *
 * <p>근거를 낸 기관({@code source_institution_ids}) 중에서 고른다. <b>저장하지 않고 계산한다</b> —
 * 이전 규칙("근거를 최다 제공한 기관")은 {@code claim_ids → summary_result → institution_id} 로 거슬러
 * 세야 해서 컬럼이 필요했지만, 종류 우선순위는 이미 있는 두 값으로 바로 나온다.
 *
 * <p>동점을 id 로 가르는 이유는 같은 종류 안에서는 어느 기관이 승인해도 의미 차이가 없고,
 * <b>결정적이기만 하면 되기 때문</b>이다 (#206 김근찬 확인). 결정적이지 않으면 같은 인사이트의 승인
 * 주체가 조회할 때마다 달라진다.
 */
@Slf4j
/*
 * public 이다. 보호자 메일도 "누가 정리했나" 를 화면과 같은 규칙으로 적어야 한다 (service.notify).
 * 규칙이 갈리면 메일의 기관과 화면의 기관이 달라진다.
 */
public final class PrimarySource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * enum 선언 순서에 기대지 않는다. 순서를 바꾸는 것만으로 승인 주체가 조용히 달라지면 안 된다.
     * 여기 없는 종류가 생기면 맨 뒤로 보낸다 — 새 종류가 모르는 사이에 승인권을 가져가지 않게 한다.
     */
    private static final List<OrganizationType> PRIORITY =
            List.of(OrganizationType.SCHOOL, OrganizationType.CENTER, OrganizationType.ACTIVITY_SUPPORT);

    private PrimarySource() {
    }

    /** 근거를 낸 기관 id. AI 가 쓴 JSON 이 깨져 있으면 빈 목록이다 — 그 인사이트는 아무에게도 보이지 않는다. */
    public static List<Long> sourceIdsOf(InsightResult insight) {
        return sourceIdsOf(insight.getId(), insight.getSourceInstitutionIds());
    }

    /** {@link #sourceIdsOf(InsightResult)} 를 엔티티 없이 — 판정 칸만 읽은 경우({@code InsightKey}). */
    public static List<Long> sourceIdsOf(Long insightId, String sourceInstitutionIds) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : readArray(insightId, sourceInstitutionIds)) {
            if (node.canConvertToLong()) {
                ids.add(node.asLong());
            }
        }
        return ids;
    }

    /**
     * 승인 주체. 근거 기관이 하나도 없거나 그 기관들이 모두 사라졌으면 {@code null} 이다 — 그러면 아무도
     * 승인할 수 없다. 조용히 아무나 승인하게 두는 것보다 낫다 (deny-by-default).
     */
    public static Organization of(List<Long> sourceIds, Map<Long, Organization> organizations) {
        return sourceIds.stream()
                .map(organizations::get)
                .filter(org -> org != null)
                .min(Comparator.comparingInt(PrimarySource::rankOf).thenComparing(Organization::getId))
                .orElse(null);
    }

    /**
     * 그 기관이 이 인사이트를 보고 승인할 수 있는지. 조회와 결정이 같은 함수를 쓴다.
     *
     * <ul>
     * <li>근거를 내지 않은 기관 → <b>404</b>. 남의 아이 인사이트가 있는지조차 알려주지 않는다</li>
     * <li>근거는 냈지만 승인 주체가 아님 → <b>403</b>. 자기 기록이 쓰인 것은 아는 기관이라 숨길 게 없다</li>
     * </ul>
     */
    static Organization requireApprover(InsightResult insight, Long organizationId,
            Map<Long, Organization> organizations) {
        return requireApprover(insight.getId(), sourceIdsOf(insight), organizationId, organizations);
    }

    /** {@link #requireApprover(InsightResult, Long, Map)} 를 근거 기관 id 로 — 같은 404 · 403 이다. */
    static Organization requireApprover(Long insightId, List<Long> sourceIds, Long organizationId,
            Map<Long, Organization> organizations) {
        if (!sourceIds.contains(organizationId)) {
            throw new InsightException(InsightErrorCode.NOT_FOUND, insightId);
        }
        Organization primary = of(sourceIds, organizations);
        if (primary == null || !primary.getId().equals(organizationId)) {
            throw new InsightException(InsightErrorCode.NOT_PRIMARY_SOURCE, insightId);
        }
        return primary;
    }

    private static int rankOf(Organization organization) {
        int rank = PRIORITY.indexOf(organization.getType());
        return rank < 0 ? Integer.MAX_VALUE : rank;
    }

    private static JsonNode readArray(Long insightId, String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node.isArray() ? node : MAPPER.createArrayNode();
        } catch (JsonProcessingException e) {
            log.warn("failed to parse insight_result.source_institution_ids id={}", insightId, e);
            return MAPPER.createArrayNode();
        }
    }
}
