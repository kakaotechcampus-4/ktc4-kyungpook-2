package com.itda.backend.service.child;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.request.child.RegisterChildRequest;
import com.itda.backend.dto.response.child.ChildRosterResponse;
import com.itda.backend.dto.response.child.RegisteredChildResponse;
import com.itda.backend.dto.response.child.RegisteredChildResponse.RegisteredChild;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.global.exception.ConstraintViolations;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChildService {

    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final UserService userService;
    private final InvitationService invitationService;
    private final ConsentStateReader consentStateReader;
    private final ConsentService consentService;

    @Transactional(readOnly = true)
    public List<ChildRosterResponse> getRoster(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Map<Long, String> externalIds = externalIdsByChildId(organizationId);
        List<Child> children = childRepository.findByOrganizationId(organizationId);
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(
                children.stream().map(Child::getId).toList());
        return children.stream()
                .map(child -> toRosterResponse(child, externalIds.get(child.getId()),
                        consents.getOrDefault(child.getId(), Map.of())
                                .getOrDefault(organizationId, ConsentState.NOT_GRANTED)))
                .toList();
    }

    private Map<Long, String> externalIdsByChildId(Long organizationId) {
        Map<Long, String> externalIds = new HashMap<>();
        for (ChildOrganization link : childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)) {
            externalIds.put(link.getChildId(), link.getExternalId());
        }
        return externalIds;
    }

    @Transactional
    public RegisteredChildResponse register(String userId, RegisterChildRequest request) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        String externalId = request.externalId();
        if (externalId != null && childOrganizationRepository.existsByOrganizationIdAndExternalId(organizationId, externalId)) {
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }
        if (childRepository.existsInOrganization(organizationId, request.name(), request.birthDateValue())) {
            throw new ChildException(ChildErrorCode.DUPLICATE_CHILD);
        }
        Child child = childRepository.save(Child.of(request.name(), request.birthDateValue()));
        try {
            childOrganizationRepository.saveAndFlush(ChildOrganization.of(child.getId(), organizationId, externalId));
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateExternalId(e)) {
                throw e;
            }
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }
        return new RegisteredChildResponse(
                new RegisteredChild(
                        String.valueOf(child.getId()),
                        child.getName(),
                        child.getBirthdate().toString(),
                        externalId,
                        child.getStatus()),
                invitationService.issue(organizationId, child.getId()));
    }

    @Transactional
    public void removeFromRoster(String userId, Long childId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Long actorId = userService.getUserIdOf(userId);
        Child child = childRepository.findByIdForUpdate(childId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_FOUND));
        ChildOrganization link = childOrganizationRepository
                .findByChildIdAndOrganizationIdForUpdate(childId, organizationId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_FOUND));

        link.delete();
        invitationService.cancelLiveForRosterRemoval(childId, organizationId);
        consentService.revokeForRosterRemoval(child, organizationId, actorId);
    }

    public static ChildRosterResponse toRosterResponse(Child child, String externalId, ConsentState consent) {
        return new ChildRosterResponse(
                String.valueOf(child.getId()),
                child.getName(),
                child.getBirthdate().toString(),
                externalId,
                child.getStatus(),
                consent);
    }

    public static boolean isDuplicateExternalId(DataIntegrityViolationException exception) {
        return ConstraintViolations.isViolationOf(exception, ChildOrganization.UK_ORGANIZATION_EXTERNAL_ID);
    }
}
