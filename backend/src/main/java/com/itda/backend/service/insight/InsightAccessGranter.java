package com.itda.backend.service.insight;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightAccessGranter {

    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional
    public void grantForLinkedGuardian(Long childId, Long guardianId, LocalDateTime at) {
        List<InsightResult> approved =
                insightResultRepository.findByChildIdAndStatusOrderByIdAsc(childId, InsightStatus.APPROVED);
        if (approved.isEmpty()) {
            return;
        }
        List<SharingHistory> grants = sharingHistoryRepository
                .findByInsightResultIdIn(approved.stream().map(InsightResult::getId).toList());
        Map<Long, SharingHistory> mine = new HashMap<>();
        Map<Long, Long> senders = new HashMap<>();
        for (SharingHistory grant : grants) {
            senders.putIfAbsent(grant.getInsightResultId(), grant.getSenderOrganizationId());
            if (grant.getReceiverType() == ReceiverType.GUARDIAN && grant.getReceiverId().equals(guardianId)) {
                mine.put(grant.getInsightResultId(), grant);
            }
        }
        Map<Long, Organization> organizations = organizationsWithoutSender(approved, senders);

        int reopened = 0;
        List<SharingHistory> opened = new ArrayList<>();
        for (InsightResult insight : approved) {
            SharingHistory existing = mine.get(insight.getId());
            if (existing != null) {
                if (!existing.isActive()) {
                    existing.regrant(at);
                    reopened++;
                }
                continue;
            }
            Long senderId = senders.get(insight.getId());
            if (senderId == null) {
                Organization primary = PrimarySource.of(PrimarySource.sourceIdsOf(insight), organizations);
                senderId = primary == null ? null : primary.getId();
            }
            if (senderId == null) {
                log.warn("approved insight has no sender, skip guardian grant insightId={} guardianId={}",
                        insight.getId(), guardianId);
                continue;
            }
            opened.add(SharingHistory.grant(childId, insight.getId(), senderId, ReceiverType.GUARDIAN, guardianId));
        }
        sharingHistoryRepository.saveAll(opened);
        if (!opened.isEmpty() || reopened > 0) {
            log.info("insight access granted to linked guardian childId={} guardianId={} opened={} reopened={}",
                    childId, guardianId, opened.size(), reopened);
        }
    }

    private Map<Long, Organization> organizationsWithoutSender(List<InsightResult> approved, Map<Long, Long> senders) {
        List<Long> sourceIds = approved.stream()
                .filter(insight -> !senders.containsKey(insight.getId()))
                .flatMap(insight -> PrimarySource.sourceIdsOf(insight).stream())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return organizationRepository.findAllById(sourceIds).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
    }
}
