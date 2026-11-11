package com.itda.backend.service.sharing;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.dto.response.sharing.InboxDetailResponse;
import com.itda.backend.dto.response.sharing.InboxItemResponse;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstitutionInboxService {

    private final UserService userService;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final SharingReadRecorder sharingReadRecorder;
    private final InsightResultRepository insightResultRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;

    @Transactional(readOnly = true)
    public List<InboxItemResponse> getInbox(String userId) {
        Readable found = readableGrants(userService.getOrganizationIdOf(userId));
        List<SharingHistory> readable = found.grants();
        if (readable.isEmpty()) {
            return List.of();
        }
        Map<Long, Child> children = found.children();
        Map<Long, ChildOrganization> enrollments = found.enrollments();

        Map<Long, InsightResult> insights = insightResultRepository
                .findAllById(readable.stream().map(SharingHistory::getInsightResultId).toList()).stream()
                .collect(Collectors.toMap(InsightResult::getId, Function.identity()));
        Map<Long, Organization> senders = organizationRepository
                .findAllById(readable.stream().map(SharingHistory::getSenderOrganizationId).distinct().toList())
                .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));

        return readable.stream()
                .map(grant -> {
                    InsightResult insight = insights.get(grant.getInsightResultId());
                    if (insight == null) {
                        log.warn("sharing grant points at a missing insight insightId={}",
                                grant.getInsightResultId());
                        return null;
                    }
                    return toItem(grant, insight, children.get(grant.getChildId()),
                            enrollments.get(grant.getChildId()), senders.get(grant.getSenderOrganizationId()));
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public int countUnread(String userId) {
        return (int) readableGrants(userService.getOrganizationIdOf(userId)).grants().stream()
                .filter(grant -> grant.getReadAt() == null)
                .count();
    }

    private Readable readableGrants(Long organizationId) {
        List<SharingHistory> received = sharingHistoryRepository
                .findByReceiverTypeAndReceiverIdAndRevokedAtIsNullOrderByIdDesc(
                        ReceiverType.ORGANIZATION, organizationId);
        if (received.isEmpty()) {
            return Readable.NONE;
        }

        Map<Long, ChildOrganization> enrollments = childOrganizationRepository
                .findByOrganizationIdAndDeletedAtIsNull(organizationId).stream()
                .collect(Collectors.toMap(ChildOrganization::getChildId, Function.identity(), (a, b) -> a));
        Set<Long> childIds = received.stream().map(SharingHistory::getChildId).collect(Collectors.toSet());
        Map<Long, Child> children = childRepository.findByIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(childIds);

        List<SharingHistory> readable = received.stream()
                .filter(grant -> OrganizationGrantVisibility.readable(grant, children.get(grant.getChildId()),
                        enrollments.get(grant.getChildId()),
                        consents.getOrDefault(grant.getChildId(), Map.of())
                                .getOrDefault(organizationId, ConsentState.NOT_GRANTED)))
                .toList();
        return new Readable(readable, children, enrollments);
    }

    private record Readable(List<SharingHistory> grants, Map<Long, Child> children,
            Map<Long, ChildOrganization> enrollments) {
        static final Readable NONE = new Readable(List.of(), Map.of(), Map.of());
    }

    @Transactional
    public InboxDetailResponse getInboxItem(String userId, Long insightId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        SharingHistory grant = sharingReadRecorder.lockActive(insightId, ReceiverType.ORGANIZATION, organizationId);
        Long childId = grant.getChildId();

        Child child = childRepository.findById(childId).orElse(null);
        ChildOrganization enrollment = childOrganizationRepository
                .findByChildIdAndOrganizationId(childId, organizationId).orElse(null);
        ConsentState consent = consentStateReader.stateOf(childId, organizationId);
        if (!OrganizationGrantVisibility.readable(grant, child, enrollment, consent)) {
            throw notFound(insightId);
        }
        InsightResult insight = insightResultRepository.findById(insightId).orElse(null);
        if (insight == null) {
            log.warn("sharing grant points at a missing insight insightId={}", insightId);
            throw notFound(insightId);
        }

        SharingHistory read = sharingReadRecorder.markRead(grant);
        Organization sender = organizationRepository.findById(read.getSenderOrganizationId()).orElse(null);
        InboxItemResponse item = toItem(read, insight, child, enrollment, sender);
        return new InboxDetailResponse(item.id(), item.childId(), item.childName(), item.externalId(),
                item.from(), item.period(), item.receivedAt(), item.readAt(), insight.getContent());
    }

    private static InboxItemResponse toItem(SharingHistory grant, InsightResult insight, Child child,
            ChildOrganization enrollment, Organization sender) {
        return new InboxItemResponse(
                String.valueOf(insight.getId()),
                String.valueOf(child.getId()),
                child.getName(),
                enrollment.getExternalId(),
                InstitutionResponse.fromNullable(sender),
                GuardianInsightService.periodLabel(insight.getPeriodFrom()),
                grant.getGrantedAt(),
                grant.getReadAt());
    }

    private static SharingException notFound(Long insightId) {
        return new SharingException(SharingErrorCode.NOT_FOUND, insightId);
    }
}
