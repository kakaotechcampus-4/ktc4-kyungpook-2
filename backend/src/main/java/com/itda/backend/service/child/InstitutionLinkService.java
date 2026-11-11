package com.itda.backend.service.child;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.child.ChildOrganizationRequest;
import com.itda.backend.domain.child.ChildOrganizationRequestStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.child.ChildRosterResponse;
import com.itda.backend.dto.response.child.InstitutionLinkRequestResponse;
import com.itda.backend.dto.response.child.PendingLinkRequestResponse;
import com.itda.backend.dto.response.child.PendingLinkRequestResponse.RequestedChild;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.child.InstitutionLinkErrorCode;
import com.itda.backend.exception.child.InstitutionLinkException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildOrganizationRequestRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.invitation.InviteCodeGenerator;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InstitutionLinkService {

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChildOrganizationRequestRepository childOrganizationRequestRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;
    private final Clock clock;

    @Transactional(readOnly = true)
    public InstitutionResponse lookup(String userId, String rawCode) {
        userService.getParentIdOf(userId);
        return InstitutionResponse.from(organizationByCode(rawCode));
    }

    @Transactional
    public InstitutionLinkRequestResponse request(String userId, Long childId, String rawCode) {
        Long parentId = userService.getParentIdOf(userId);
        Organization organization = organizationByCode(rawCode);

        guardianChildGuard.lockLinkedChild(parentId, childId);
        if (childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(
                childId, organization.getId())) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_ALREADY_LINKED);
        }
        ChildOrganizationRequest request = childOrganizationRequestRepository
                .findFirstByChildIdAndOrganizationIdAndRequestedByAndStatus(
                        childId, organization.getId(), parentId, ChildOrganizationRequestStatus.PENDING)
                .orElseGet(() -> childOrganizationRequestRepository.save(
                        ChildOrganizationRequest.request(childId, organization.getId(), parentId)));
        return new InstitutionLinkRequestResponse(InstitutionResponse.from(organization),
                InstitutionLinkRequestResponse.PENDING, toOffset(request.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public List<PendingLinkRequestResponse> pendingRequests(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Map<Long, List<ChildOrganizationRequest>> byChild = childOrganizationRequestRepository
                .findByOrganizationIdAndStatusOrderByCreatedAtAscIdAsc(
                        organizationId, ChildOrganizationRequestStatus.PENDING)
                .stream()
                .collect(Collectors.groupingBy(ChildOrganizationRequest::getChildId, LinkedHashMap::new,
                        Collectors.toList()));
        if (byChild.isEmpty()) {
            return List.of();
        }
        Map<Long, Child> children = childRepository.findByIdInAndDeletedAtIsNull(byChild.keySet()).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        return byChild.entrySet().stream()
                .filter(entry -> children.containsKey(entry.getKey()))
                .map(entry -> {
                    Child child = children.get(entry.getKey());
                    List<ChildOrganizationRequest> requests = entry.getValue();
                    return new PendingLinkRequestResponse(
                            new RequestedChild(String.valueOf(child.getId()), child.getName(),
                                    child.getBirthdate().toString()),
                            (int) requests.stream().map(ChildOrganizationRequest::getRequestedBy).distinct().count(),
                            toOffset(requests.get(0).getCreatedAt()));
                })
                .toList();
    }

    @Transactional
    public ChildRosterResponse accept(String userId, Long childId, String externalId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Long actorId = userService.getUserIdOf(userId);
        Child child = lockLiveChild(childId);

        Optional<ChildOrganization> existing =
                childOrganizationRepository.findByChildIdAndOrganizationIdForUpdate(childId, organizationId);
        if (existing.filter(link -> !link.isDeleted()).isPresent()) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_ALREADY_LINKED);
        }
        List<ChildOrganizationRequest> pending = lockPending(childId, organizationId);

        if (childOrganizationRepository.existsByOrganizationIdAndExternalId(organizationId, externalId)) {
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }
        ChildOrganization link = existing.orElse(null);
        if (link == null) {
            link = ChildOrganization.of(childId, organizationId, externalId);
        } else {
            link.restore(externalId);
        }
        try {
            childOrganizationRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            if (!ChildService.isDuplicateExternalId(e)) {
                throw e;
            }
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }

        LocalDateTime now = now();
        pending.forEach(request -> request.accept(actorId, now));
        return ChildService.toRosterResponse(child, externalId, consentStateReader.stateOf(childId, organizationId));
    }

    @Transactional
    public void reject(String userId, Long childId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Long actorId = userService.getUserIdOf(userId);
        lockLiveChild(childId);
        List<ChildOrganizationRequest> pending = lockPending(childId, organizationId);
        LocalDateTime now = now();
        pending.forEach(request -> request.reject(actorId, now));
    }

    @Transactional
    public void cancelForUnlinkedGuardian(Long childId, Long guardianId) {
        LocalDateTime now = now();
        childOrganizationRequestRepository.findPendingByChildIdAndRequestedByForUpdate(childId, guardianId)
                .forEach(request -> request.cancel(now));
    }

    private Organization organizationByCode(String rawCode) {
        String code = InviteCodeGenerator.normalize(rawCode);
        if (code == null) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_CODE_NOT_FOUND);
        }
        return organizationRepository.findByCode(code)
                .orElseThrow(() -> new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_CODE_NOT_FOUND));
    }

    private Child lockLiveChild(Long childId) {
        return childRepository.findByIdForUpdate(childId)
                .filter(child -> !child.isDeleted())
                .orElseThrow(() -> new InstitutionLinkException(InstitutionLinkErrorCode.LINK_REQUEST_NOT_FOUND));
    }

    private List<ChildOrganizationRequest> lockPending(Long childId, Long organizationId) {
        List<ChildOrganizationRequest> pending =
                childOrganizationRequestRepository.findPendingByChildIdAndOrganizationIdForUpdate(childId, organizationId);
        if (pending.isEmpty()) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.LINK_REQUEST_NOT_FOUND);
        }
        return pending;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(clock.getZone()).toOffsetDateTime();
    }
}
