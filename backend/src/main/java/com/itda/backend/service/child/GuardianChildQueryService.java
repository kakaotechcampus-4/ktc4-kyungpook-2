package com.itda.backend.service.child;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildCareInfo;
import com.itda.backend.domain.child.ChildOrganizationRequest;
import com.itda.backend.domain.child.ChildOrganizationRequestStatus;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.child.ChildCareInfoResponse;
import com.itda.backend.dto.response.child.GuardianChildResponse;
import com.itda.backend.dto.response.child.GuardianChildResponse.ChildInstitution;
import com.itda.backend.dto.response.child.GuardianChildResponse.LinkRequest;
import com.itda.backend.dto.response.child.GuardianHomeResponse;
import com.itda.backend.dto.response.child.ParentActivityResponse;
import com.itda.backend.dto.response.consent.PendingConsentRequestResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildCareInfoRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildOrganizationRequestRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.sharing.GuardianInsightService;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GuardianChildQueryService {

    private static final int ACTIVITY_LIMIT = 5;

    private final UserService userService;
    private final ChildGuardianRepository childGuardianRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ChildOrganizationRequestRepository childOrganizationRequestRepository;
    private final ChildCareInfoRepository childCareInfoRepository;
    private final ConsentStateReader consentStateReader;
    private final GuardianJournalQueryService guardianJournalQueryService;
    private final GuardianInsightService guardianInsightService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<GuardianChildResponse> children(String userId) {
        Long parentId = userService.getParentIdOf(userId);
        List<Long> childIds = childGuardianRepository.findByUserIdAndDeletedAtIsNullOrderByLinkedAtAscIdAsc(parentId)
                .stream()
                .map(ChildGuardian::getChildId)
                .distinct()
                .toList();
        return assemble(parentId, childIds);
    }

    @Transactional(readOnly = true)
    public GuardianHomeResponse home(String userId, Long childId) {
        Long parentId = userService.getParentIdOf(userId);
        GuardianChildResponse child = linkedChild(parentId, childId);
        List<ParentActivityResponse> activity = Stream.concat(
                        guardianJournalQueryService.recentActivity(childId).stream(),
                        guardianInsightService.recentActivity(parentId, childId, ACTIVITY_LIMIT).stream())
                .sorted(Comparator.comparing(ActivityLine::at).reversed())
                .limit(ACTIVITY_LIMIT)
                .map(ActivityLine::line)
                .toList();
        return new GuardianHomeResponse(child, activity);
    }

    @Transactional(readOnly = true)
    public List<PendingConsentRequestResponse> pendingRequests(String userId, Long childId) {
        return linkedChild(userId, childId).institutions().stream()
                .filter(institution -> institution.consent() == ConsentState.NOT_GRANTED)
                .map(institution -> new PendingConsentRequestResponse(institution.institution()))
                .toList();
    }

    private GuardianChildResponse linkedChild(String userId, Long childId) {
        return linkedChild(userService.getParentIdOf(userId), childId);
    }

    private GuardianChildResponse linkedChild(Long parentId, Long childId) {
        if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_LINKED);
        }
        return assemble(parentId, List.of(childId)).stream()
                .findFirst()
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_LINKED));
    }

    private List<GuardianChildResponse> assemble(Long parentId, List<Long> childIds) {
        if (childIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Child> children = childRepository.findByIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<Long, List<Long>> enrolled = childOrganizationRepository.findOrganizationIdsByChildIdIn(childIds);
        Map<Long, List<ChildOrganizationRequest>> requested = childOrganizationRequestRepository
                .findByChildIdInAndStatusOrderByCreatedAtAscIdAsc(childIds, ChildOrganizationRequestStatus.PENDING)
                .stream()
                .collect(Collectors.groupingBy(ChildOrganizationRequest::getChildId));
        Map<Long, Organization> organizations = organizationsOf(enrolled, requested);
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(childIds);
        Set<Long> unread = guardianInsightService.childIdsWithUnread(parentId, childIds);
        Map<Long, ChildCareInfo> careInfos = childCareInfoRepository.findByChildIdIn(childIds).stream()
                .collect(Collectors.toMap(ChildCareInfo::getChildId, Function.identity()));

        return childIds.stream()
                .map(children::get)
                .filter(Objects::nonNull)
                .map(child -> toResponse(child,
                        enrolled.getOrDefault(child.getId(), List.of()),
                        requested.getOrDefault(child.getId(), List.of()),
                        organizations,
                        consents.getOrDefault(child.getId(), Map.of()),
                        careInfos.get(child.getId()),
                        unread.contains(child.getId())))
                .toList();
    }

    private Map<Long, Organization> organizationsOf(
            Map<Long, List<Long>> enrolled, Map<Long, List<ChildOrganizationRequest>> requested) {
        List<Long> ids = Stream.concat(
                        enrolled.values().stream().flatMap(List::stream),
                        requested.values().stream().flatMap(List::stream).map(ChildOrganizationRequest::getOrganizationId))
                .distinct()
                .toList();
        Map<Long, Organization> organizations = new HashMap<>();
        if (!ids.isEmpty()) {
            organizationRepository.findAllById(ids).forEach(org -> organizations.put(org.getId(), org));
        }
        return organizations;
    }

    private GuardianChildResponse toResponse(Child child, List<Long> organizationIds,
            List<ChildOrganizationRequest> requests, Map<Long, Organization> organizations,
            Map<Long, ConsentState> consentOfChild, ChildCareInfo careInfo, boolean hasUnreadInsight) {
        List<ChildInstitution> institutions = organizationIds.stream()
                .distinct()
                .map(organizations::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Organization::getId))
                .map(org -> new ChildInstitution(InstitutionResponse.from(org),
                        consentOfChild.getOrDefault(org.getId(), ConsentState.NOT_GRANTED)))
                .toList();
        return new GuardianChildResponse(
                String.valueOf(child.getId()),
                child.getName(),
                child.getBirthdate().toString(),
                child.getStatus(),
                institutions,
                linkRequestsOf(requests, organizations),
                careInfo == null ? ChildCareInfoResponse.empty() : ChildCareInfoResponse.from(careInfo),
                hasUnreadInsight);
    }

    private List<LinkRequest> linkRequestsOf(
            List<ChildOrganizationRequest> requests, Map<Long, Organization> organizations) {
        Map<Long, ChildOrganizationRequest> earliest = new LinkedHashMap<>();
        requests.forEach(request -> earliest.putIfAbsent(request.getOrganizationId(), request));
        return earliest.values().stream()
                .filter(request -> organizations.containsKey(request.getOrganizationId()))
                .map(request -> new LinkRequest(
                        InstitutionResponse.from(organizations.get(request.getOrganizationId())),
                        request.getCreatedAt().atZone(clock.getZone()).toOffsetDateTime()))
                .toList();
    }
}
