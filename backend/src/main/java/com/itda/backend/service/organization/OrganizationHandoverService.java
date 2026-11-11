package com.itda.backend.service.organization;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.organization.OrganizationHandover;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.organization.IssuedHandoverResponse;
import com.itda.backend.exception.organization.OrganizationErrorCode;
import com.itda.backend.exception.organization.OrganizationException;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.global.exception.ConstraintViolations;
import com.itda.backend.repository.organization.HandoverTarget;
import com.itda.backend.repository.organization.OrganizationHandoverRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.InviteCodeGenerator;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.user.WithdrawalProperties;

@Service
public class OrganizationHandoverService {

    static final Duration TTL = Duration.ofHours(12);
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final OrganizationHandoverRepository handoverRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public OrganizationHandoverService(OrganizationHandoverRepository handoverRepository, UserRepository userRepository,
            InviteCodeGenerator inviteCodeGenerator, Clock clock, ApplicationEventPublisher events,
            WithdrawalProperties withdrawalProperties) {
        this.handoverRepository = handoverRepository;
        this.userRepository = userRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.clock = clock;
        this.events = events;
        this.enabled = withdrawalProperties.enabled();
    }

    @Transactional
    public IssuedHandoverResponse issue(String principal) {
        if (!enabled) {
            throw new UserException(UserErrorCode.WITHDRAWAL_NOT_AVAILABLE);
        }
        User staff = lockStaff(principal);
        LocalDateTime now = now();
        cancelLive(staff.getOrganizationId(), now);

        OrganizationHandover handover = OrganizationHandover.issue(
                staff.getOrganizationId(), staff.getId(), unusedCode(), now.plus(TTL));
        try {
            handoverRepository.saveAndFlush(handover);
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.isViolationOf(e, OrganizationHandover.UK_CODE)) {
                throw e;
            }
            throw new OrganizationException(OrganizationErrorCode.HANDOVER_CODE_ISSUE_FAILED);
        }
        return new IssuedHandoverResponse(handover.getCode(),
                handover.getExpiresAt().atZone(clock.getZone()).toOffsetDateTime());
    }

    @Transactional
    public void cancel(String principal) {
        User staff = lockStaff(principal);
        cancelLive(staff.getOrganizationId(), now());
    }

    @Transactional
    public Long accept(User successor, String rawCode) {
        String code = InviteCodeGenerator.normalize(rawCode);
        if (!enabled || code == null) {
            throw invalidCode();
        }
        HandoverTarget target = handoverRepository.findTargetByCode(code).orElseThrow(OrganizationHandoverService::invalidCode);
        if (target.issuedBy().equals(successor.getId())) {
            throw invalidCode();
        }
        User former = userRepository.findByIdForUpdate(target.issuedBy()).orElseThrow(OrganizationHandoverService::invalidCode);
        if (former.isDeleted() || former.isWithdrawing() || !former.isOrganization()
                || !target.organizationId().equals(former.getOrganizationId())) {
            throw invalidCode();
        }
        OrganizationHandover handover = handoverRepository.findByIdForUpdate(target.id())
                .orElseThrow(OrganizationHandoverService::invalidCode);
        LocalDateTime now = now();
        if (!handover.isUsable(now)) {
            throw invalidCode();
        }

        handover.accept(successor.getId(), now);
        former.requestHandoverWithdrawal(now);
        events.publishEvent(new OrganizationHandoverAccepted(former.getId()));
        return handover.getOrganizationId();
    }

    private static OrganizationException invalidCode() {
        return new OrganizationException(OrganizationErrorCode.INVALID_HANDOVER_CODE);
    }

    private void cancelLive(Long organizationId, LocalDateTime now) {
        handoverRepository.findLiveForUpdate(organizationId, now).forEach(live -> live.cancel(now));
    }

    private User lockStaff(String principal) {
        User user = userRepository.findActiveByIdForUpdate(UserService.parseUserId(principal))
                .orElseThrow(() -> new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));
        if (user.isWithdrawing()) {
            throw new UserException(UserErrorCode.WITHDRAWAL_IN_PROGRESS);
        }
        if (!user.isOrganization() || user.getOrganizationId() == null) {
            throw new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);
        }
        return user;
    }

    private String unusedCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (!handoverRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new OrganizationException(OrganizationErrorCode.HANDOVER_CODE_ISSUE_FAILED);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
