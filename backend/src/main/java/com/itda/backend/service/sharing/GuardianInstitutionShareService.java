package com.itda.backend.service.sharing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.guardian.InstitutionSharesResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.child.GuardianChildGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianInstitutionShareService {

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final InsightResultRepository insightResultRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;

    @Transactional(readOnly = true)
    public List<InstitutionSharesResponse> getShares(String userId, Long childId) {
        Long parentId = userService.getParentIdOf(userId);
        Child child = guardianChildGuard.requireLinkedChild(parentId, childId);

        List<SharingHistory> grants = sharingHistoryRepository.findByChildIdAndReceiverType(
                childId, ReceiverType.ORGANIZATION);
        if (grants.isEmpty()) {
            return List.of();
        }

        Map<Long, InsightResult> insights = insightResultRepository
                .findAllById(grants.stream().map(SharingHistory::getInsightResultId).distinct().toList()).stream()
                .collect(Collectors.toMap(InsightResult::getId, Function.identity()));
        Map<Long, Organization> organizations = organizationRepository
                .findAllById(grants.stream()
                        .flatMap(grant -> Stream.of(grant.getReceiverId(), grant.getSenderOrganizationId()))
                        .distinct()
                        .toList())
                .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));
        Map<Long, ChildOrganization> enrollments = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId)
                .stream()
                .collect(Collectors.toMap(ChildOrganization::getOrganizationId, Function.identity(), (a, b) -> a));
        Map<Long, ConsentState> consents = consentStateReader.statesOf(List.of(childId))
                .getOrDefault(childId, Map.of());

        Map<Long, List<InstitutionSharesResponse.SharedInsight>> byReceiver = new LinkedHashMap<>();
        grants.stream()
                .sorted(Comparator.comparing(SharingHistory::getGrantedAt).reversed()
                        .thenComparing(SharingHistory::getId, Comparator.reverseOrder()))
                .forEach(grant -> {
                    InsightResult insight = insights.get(grant.getInsightResultId());
                    if (insight == null) {
                        log.warn("sharing grant points at a missing insight insightId={}", grant.getInsightResultId());
                        return;
                    }
                    Long receiverId = grant.getReceiverId();
                    boolean visible = OrganizationGrantVisibility.readable(grant, child, enrollments.get(receiverId),
                            consents.getOrDefault(receiverId, ConsentState.NOT_GRANTED));
                    Organization sender = organizations.get(grant.getSenderOrganizationId());
                    byReceiver.computeIfAbsent(receiverId, k -> new ArrayList<>())
                            .add(new InstitutionSharesResponse.SharedInsight(
                                    String.valueOf(insight.getId()),
                                    GuardianInsightService.periodLabel(insight.getPeriodFrom()),
                                    InstitutionResponse.fromNullable(sender),
                                    grant.getGrantedAt(),
                                    visible,
                                    visible ? null : grant.getRevokedAt()));
                });

        return byReceiver.entrySet().stream()
                .map(entry -> {
                    Organization receiver = organizations.get(entry.getKey());
                    if (receiver == null) {
                        log.warn("sharing grant points at a missing organization organizationId={}", entry.getKey());
                        return null;
                    }
                    return new InstitutionSharesResponse(InstitutionResponse.from(receiver), entry.getValue());
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(response -> response.institution().name()))
                .toList();
    }
}
