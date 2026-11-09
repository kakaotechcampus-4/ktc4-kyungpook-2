package com.itda.backend.service.insight;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightAccessRevoker {

    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;

    @Transactional
    public void revokeForInstitution(Long childId, Long organizationId, ConsentRevokeReason reason,
            LocalDateTime at) {
        List<Long> fedByThem = insightsFedBy(childId, organizationId);
        Stream<SharingHistory> onTheirMaterial = fedByThem.isEmpty() ? Stream.of()
                : sharingHistoryRepository
                        .findByInsightResultIdInAndReceiverTypeAndRevokedAtIsNull(
                                fedByThem, ReceiverType.ORGANIZATION)
                        .stream();
        Stream<SharingHistory> theirs = sharingHistoryRepository
                .findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
                        childId, ReceiverType.ORGANIZATION, organizationId)
                .stream();

        Map<Long, SharingHistory> revoked = Stream.concat(theirs, onTheirMaterial)
                .collect(Collectors.toMap(SharingHistory::getId, history -> history, (a, b) -> a));
        revoked.values().forEach(history -> history.revoke(reason, at));
        if (!revoked.isEmpty()) {
            log.info("insight access revoked by consent childId={} organizationId={} material={} grants={}",
                    childId, organizationId, fedByThem.size(), revoked.size());
        }
    }

    @Transactional
    public void revokeForGuardian(Long childId, Long guardianId, ConsentRevokeReason reason, LocalDateTime at) {
        List<SharingHistory> revoked = sharingHistoryRepository
                .findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
                        childId, ReceiverType.GUARDIAN, guardianId);
        revoked.forEach(history -> history.revoke(reason, at));
        if (!revoked.isEmpty()) {
            log.info("guardian access revoked childId={} guardianId={} grants={}",
                    childId, guardianId, revoked.size());
        }
    }

    private List<Long> insightsFedBy(Long childId, Long organizationId) {
        return insightResultRepository.findByChildIdInOrderByIdDesc(Set.of(childId)).stream()
                .filter(insight -> PrimarySource.isFedBy(insight, organizationId))
                .map(InsightResult::getId)
                .toList();
    }
}
