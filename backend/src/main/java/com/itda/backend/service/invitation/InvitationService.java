package com.itda.backend.service.invitation;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.dto.response.invitation.AcceptedInvitationResponse;
import com.itda.backend.dto.response.invitation.IssuedInvitationResponse;
import com.itda.backend.dto.response.invitation.LiveInvitationResponse;
import com.itda.backend.exception.invitation.InvitationErrorCode;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.invitation.InvitationRepository;
import com.itda.backend.repository.invitation.InvitationTarget;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.child.OrganizationChildGuard;
import com.itda.backend.service.insight.InsightAccessGranter;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InvitationService {

    static final Duration TIME_TO_LIVE = Duration.ofMinutes(10);
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final InvitationRepository invitationRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationChildGuard organizationChildGuard;
    private final InsightAccessGranter insightAccessGranter;
    private final UserService userService;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final Clock clock;

    @Transactional
    public IssuedInvitationResponse issue(Long organizationId, Long childId) {
        Invitation invitation = Invitation.issue(childId, organizationId, unusedCode(), now().plus(TIME_TO_LIVE));
        try {
            invitationRepository.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException e) {
            if (!isViolationOf(e, Invitation.UK_INVITATION_CODE)) {
                throw e;
            }
            throw new InvitationException(InvitationErrorCode.INVITE_CODE_ISSUE_FAILED);
        }
        return new IssuedInvitationResponse(
                String.valueOf(invitation.getId()), invitation.getCode(), toOffset(invitation.getExpiresAt()));
    }

    @Transactional
    public IssuedInvitationResponse issueFor(String userId, Long childId) {
        Long organizationId = organizationChildGuard.lockRosterChild(userId, childId);
        return issue(organizationId, childId);
    }

    @Transactional
    public void cancelLiveForRosterRemoval(Long childId, Long organizationId) {
        LocalDateTime now = now();
        invitationRepository.findLiveForUpdate(childId, organizationId, now)
                .forEach(invitation -> invitation.cancel(now));
    }

    @Transactional(readOnly = true)
    public List<LiveInvitationResponse> listLive(String userId, Long childId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        return invitationRepository.findLive(childId, organizationId, now()).stream()
                .map(invitation -> new LiveInvitationResponse(
                        String.valueOf(invitation.getId()),
                        invitation.getCode(),
                        toOffset(invitation.getCreatedAt()),
                        toOffset(invitation.getExpiresAt())))
                .toList();
    }

    @Transactional
    public void cancel(String userId, Long childId, Long invitationId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        Invitation invitation = invitationRepository
                .findByIdAndChildIdAndOrganizationIdForUpdate(invitationId, childId, organizationId)
                .orElseThrow(() -> new InvitationException(InvitationErrorCode.INVITATION_NOT_FOUND));
        LocalDateTime now = now();
        if (!invitation.isUsable(now)) {
            throw new InvitationException(InvitationErrorCode.INVITATION_NOT_FOUND);
        }
        invitation.cancel(now);
    }

    @Transactional
    public AcceptedInvitationResponse accept(String userId, String rawCode) {
        Long parentId = userService.lockParentForLink(userId);
        String code = InviteCodeGenerator.normalize(rawCode);
        if (code == null) {
            throw invalidCode();
        }
        InvitationTarget target = invitationRepository.findTargetByCode(code).orElseThrow(InvitationService::invalidCode);

        Child child = childRepository.findByIdForUpdate(target.childId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(InvitationService::invalidCode);
        childOrganizationRepository.findByChildIdAndOrganizationIdForUpdate(target.childId(), target.organizationId())
                .filter(link -> !link.isDeleted())
                .orElseThrow(InvitationService::invalidCode);
        Invitation invitation = invitationRepository.findByIdForUpdate(target.id())
                .orElseThrow(InvitationService::invalidCode);
        if (!invitation.isUsable(now())) {
            throw invalidCode();
        }

        linkGuardian(child.getId(), parentId, invitation.getId());
        insightAccessGranter.grantForLinkedGuardian(child.getId(), parentId, now());

        LocalDateTime now = now();
        if (!invitation.isUsable(now)) {
            throw invalidCode();
        }
        invitation.accept(parentId, now);

        String organizationName = organizationRepository.findById(target.organizationId())
                .map(organization -> organization.getName())
                .orElse(null);
        return new AcceptedInvitationResponse(
                String.valueOf(child.getId()), child.getName(), String.valueOf(target.organizationId()),
                organizationName, child.getStatus());
    }

    private void linkGuardian(Long childId, Long parentId, Long invitationId) {
        Optional<ChildGuardian> existing = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, parentId);
        if (existing.isPresent()) {
            ChildGuardian guardian = existing.get();
            if (!guardian.isDeleted()) {
                throw new InvitationException(InvitationErrorCode.LINK_ALREADY_EXISTS);
            }
            guardian.relink(invitationId);
            return;
        }
        try {
            childGuardianRepository.saveAndFlush(ChildGuardian.link(childId, parentId, invitationId));
        } catch (DataIntegrityViolationException e) {
            if (!isViolationOf(e, ChildGuardian.UK_CHILD_GUARDIAN)) {
                throw e;
            }
            throw new InvitationException(InvitationErrorCode.LINK_ALREADY_EXISTS);
        }
    }

    private String unusedCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (!invitationRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new InvitationException(InvitationErrorCode.INVITE_CODE_ISSUE_FAILED);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(clock.getZone()).toOffsetDateTime();
    }

    private static InvitationException invalidCode() {
        return new InvitationException(InvitationErrorCode.INVALID_INVITE_CODE);
    }

    private static boolean isViolationOf(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
