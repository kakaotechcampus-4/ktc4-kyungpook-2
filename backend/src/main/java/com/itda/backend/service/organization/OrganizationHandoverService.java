package com.itda.backend.service.organization;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

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
import com.itda.backend.repository.organization.OrganizationHandoverRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.InviteCodeGenerator;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.user.WithdrawalProperties;

/**
 * 기관 담당자 넘기기 (#478). 지금 담당자가 인계 코드를 발급하고, 후임이 가입할 때 코드를 넣으면 같은 기관의 담당자가 되며
 * 발급한 담당자는 탈퇴로 정리된다.
 *
 * <p>회원 서비스({@code UserService})를 주입받지 않는다 — 가입이 이 서비스를 부르므로 서로 물린다. 회원 행은 저장소로 직접 잠근다.
 *
 * <p>잠금 순서: 회원 행 → 인계 행 (DB 스키마 §3.3). 발급 · 취소는 담당자 행을, 가입은 새 회원 행 → 발급한 담당자 행을 먼저 잠근다.
 */
@Service
public class OrganizationHandoverService {

    static final Duration TTL = Duration.ofHours(12);
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final OrganizationHandoverRepository handoverRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final Clock clock;
    private final boolean enabled;

    public OrganizationHandoverService(OrganizationHandoverRepository handoverRepository, UserRepository userRepository,
            InviteCodeGenerator inviteCodeGenerator, Clock clock, WithdrawalProperties withdrawalProperties) {
        this.handoverRepository = handoverRepository;
        this.userRepository = userRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.clock = clock;
        this.enabled = withdrawalProperties.enabled();
    }

    /**
     * 새 인계 코드를 발급한다. 그 기관에 살아 있던 코드는 거둬들인다 — 살아 있는 코드는 늘 하나다.
     * 탈퇴 스위치가 꺼져 있으면 404: 인계가 끝나면 이 담당자를 탈퇴시키는데 그 길이 닫혀 있다.
     */
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

    /** 살아 있는 코드를 거둬들인다. 없어도 실패하지 않는다. */
    @Transactional
    public void cancel(String principal) {
        User staff = lockStaff(principal);
        cancelLive(staff.getOrganizationId(), now());
    }

    private void cancelLive(Long organizationId, LocalDateTime now) {
        handoverRepository.findLiveForUpdate(organizationId, now).forEach(live -> live.cancel(now));
    }

    /** 기관 담당자 행을 잠근다. 탈퇴 중이면 409, 기관 담당자가 아니면 403. */
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
