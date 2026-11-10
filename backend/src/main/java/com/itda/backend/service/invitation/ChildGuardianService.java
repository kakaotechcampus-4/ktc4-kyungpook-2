package com.itda.backend.service.invitation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.dto.response.invitation.ChildGuardianResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.invitation.InvitationErrorCode;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.invitation.InvitationRepository;
import com.itda.backend.service.child.InstitutionLinkService;
import com.itda.backend.service.child.OrganizationChildGuard;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChildGuardianService {

    private final ChildGuardianRepository childGuardianRepository;
    private final InvitationRepository invitationRepository;
    private final OrganizationChildGuard organizationChildGuard;
    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentService consentService;
    private final InstitutionLinkService institutionLinkService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ChildGuardianResponse> listGuardians(String userId, Long childId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        List<ChildGuardian> guardians = childGuardianRepository.findByChildIdAndDeletedAtIsNullOrderByLinkedAtAsc(childId);

        Map<Long, Invitation> invitations = new HashMap<>();
        for (Invitation invitation : invitationRepository.findAllById(
                guardians.stream().map(ChildGuardian::getInvitationId).toList())) {
            invitations.put(invitation.getId(), invitation);
        }

        return guardians.stream()
                .map(guardian -> {
                    Invitation invitation = invitations.get(guardian.getInvitationId());
                    boolean ours = invitation != null && organizationId.equals(invitation.getOrganizationId());
                    return new ChildGuardianResponse(
                            String.valueOf(guardian.getUserId()),
                            toOffset(guardian.getLinkedAt()),
                            ours,
                            ours ? invitation.getCode() : null);
                })
                .toList();
    }

    @Transactional
    public void unlinkByOrganization(String userId, Long childId, Long guardianUserId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Child child = childRepository.findByIdForUpdate(childId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_FOUND));
        if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_FOUND);
        }
        institutionLinkService.cancelForUnlinkedGuardian(childId, guardianUserId);
        ChildGuardian guardian = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, guardianUserId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new InvitationException(InvitationErrorCode.GUARDIAN_NOT_FOUND));
        boolean linkedByThisOrganization = invitationRepository.findById(guardian.getInvitationId())
                .map(invitation -> organizationId.equals(invitation.getOrganizationId()))
                .orElse(false);
        if (!linkedByThisOrganization) {
            throw new InvitationException(InvitationErrorCode.GUARDIAN_LINKED_BY_OTHER_ORGANIZATION);
        }
        guardian.delete();
        consentService.revokeForUnlinkedGuardian(child, guardianUserId, userService.getUserIdOf(userId));
    }

    @Transactional
    public void unlinkBySelf(String userId, Long childId) {
        unlinkGuardian(userService.getParentIdOf(userId), childId);
    }

    @Transactional
    public void unlinkAllForWithdrawal(Long parentId) {
        for (Long childId : childGuardianRepository.findLinkedChildIds(parentId)) {
            Optional<Child> child = childRepository.findByIdForUpdate(childId);
            if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
                continue;
            }
            unlinkLocked(parentId, childId, child);
        }
    }

    private void unlinkGuardian(Long parentId, Long childId) {
        unlinkLocked(parentId, childId, childRepository.findByIdForUpdate(childId));
    }

    private void unlinkLocked(Long parentId, Long childId, Optional<Child> child) {
        institutionLinkService.cancelForUnlinkedGuardian(childId, parentId);
        ChildGuardian guardian = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, parentId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new InvitationException(InvitationErrorCode.CHILD_NOT_LINKED));
        guardian.delete();
        child.ifPresent(found -> consentService.revokeForUnlinkedGuardian(found, parentId, parentId));
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(clock.getZone()).toOffsetDateTime();
    }
}
