package com.itda.backend.service.insight;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.insight.InsightResultRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InsightActivityReader {

    private final InsightResultRepository insightResultRepository;
    private final ChildOrganizationRepository childOrganizationRepository;

    @Transactional(readOnly = true)
    public long countCreatedSince(Long organizationId, LocalDateTime since) {
        List<Long> childIds = childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).distinct().toList();
        if (childIds.isEmpty()) {
            return 0;
        }
        return insightResultRepository.findByChildIdInAndCreatedAtGreaterThanEqual(childIds, since).stream()
                .filter(insight -> PrimarySource.isFedBy(insight, organizationId))
                .count();
    }
}
