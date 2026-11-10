package com.itda.backend.service.child;

import org.springframework.stereotype.Component;

import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrganizationChildGuard {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;

    public Long requireRosterChild(String userId, Long childId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (childRepository.findByIdAndDeletedAtIsNull(childId).isEmpty()
                || !childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_FOUND);
        }
        return organizationId;
    }

    public Long lockRosterChild(String userId, Long childId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        boolean onRoster = childRepository.findByIdForShare(childId).filter(child -> !child.isDeleted()).isPresent()
                && childOrganizationRepository.findByChildIdAndOrganizationIdForUpdate(childId, organizationId)
                        .filter(link -> !link.isDeleted())
                        .isPresent();
        if (!onRoster) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_FOUND);
        }
        return organizationId;
    }
}
