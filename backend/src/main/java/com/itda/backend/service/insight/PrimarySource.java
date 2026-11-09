package com.itda.backend.service.insight;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.global.json.JsonColumns;

public final class PrimarySource {

    private static final List<OrganizationType> PRIORITY =
            List.of(OrganizationType.SCHOOL, OrganizationType.CENTER, OrganizationType.ACTIVITY_SUPPORT);

    private PrimarySource() {
    }

    public static List<Long> sourceIdsOf(InsightResult insight) {
        return sourceIdsOf(insight.getId(), insight.getSourceInstitutionIds());
    }

    public static List<Long> sourceIdsOf(Long insightId, String sourceInstitutionIds) {
        return JsonColumns.readLongs(sourceInstitutionIds, "insight_result.source_institution_ids", insightId);
    }

    public static boolean isFedBy(InsightResult insight, Long organizationId) {
        return sourceIdsOf(insight).contains(organizationId);
    }

    public static Organization of(List<Long> sourceIds, Map<Long, Organization> organizations) {
        return sourceIds.stream()
                .map(organizations::get)
                .filter(org -> org != null)
                .min(Comparator.comparingInt(PrimarySource::rankOf).thenComparing(Organization::getId))
                .orElse(null);
    }

    static Organization requireApprover(InsightResult insight, Long organizationId,
            Map<Long, Organization> organizations) {
        return requireApprover(insight.getId(), sourceIdsOf(insight), organizationId, organizations);
    }

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
}
