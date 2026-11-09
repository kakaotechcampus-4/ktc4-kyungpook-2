package com.itda.backend.service.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;
import com.itda.backend.dto.request.user.SignupRequest;
import com.itda.backend.dto.response.user.CurrentUserResponse;
import com.itda.backend.dto.response.user.TermsAgreementResponse;
import com.itda.backend.exception.organization.OrganizationErrorCode;
import com.itda.backend.exception.organization.OrganizationException;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.global.exception.ConstraintViolations;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.organization.OrganizationCodeIssuer;
import com.itda.backend.service.organization.OrganizationHandoverService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationCodeIssuer organizationCodeIssuer;
    private final LoginUserTransactionService loginUserTransactionService;
    private final TermsService termsService;
    private final OrganizationHandoverService organizationHandoverService;

    public User findOrCreateByKakaoId(String kakaoId) {
        return findOrCreateByKakaoId(kakaoId, null);
    }

    public User findOrCreateByKakaoId(String kakaoId, LocalDateTime kakaoVerifiedAt) {
        try {
            return loginUserTransactionService.findOrCreate(kakaoId, kakaoVerifiedAt);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKakaoId(e)) {
                throw e;
            }
            return loginUserTransactionService.findExisting(kakaoId, kakaoVerifiedAt)
                    .orElseThrow(() -> e);
        }
    }

    private boolean isDuplicateKakaoId(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "uk_users_kakao_id".equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public CurrentUserResponse completeSignup(String principal, SignupRequest request) {
        User user = lockActive(principal);
        if (user.isSignupCompleted()) {
            throw new UserException(UserErrorCode.ALREADY_SIGNED_UP);
        }

        if (request.isHandoverSignup()) {
            Long organizationId = organizationHandoverService.accept(user, request.handoverCode());
            user.completeSignup(UserRole.ORGANIZATION, organizationId);
            Organization organization = organizationRepository.findById(organizationId).orElse(null);
            return CurrentUserResponse.from(user, organization, termsService.isAgreed(user));
        }
        if (request.isOrganizationSignup()) {
            Organization organization = createOrganization(request);
            user.completeSignup(UserRole.ORGANIZATION, organization.getId());
            return CurrentUserResponse.from(user, organization, termsService.isAgreed(user));
        }
        user.completeSignup(UserRole.PARENT, null);
        return CurrentUserResponse.from(user, null, termsService.isAgreed(user));
    }

    @Transactional
    public TermsAgreementResponse agreeTerms(String principal, List<TermKey> agreed, Map<TermKey, String> versions) {
        User user = lockActive(principal);
        termsService.agree(user, agreed, versions);
        return new TermsAgreementResponse(true);
    }

    @Transactional(readOnly = true)
    public AccountAccess accessOf(String principal) {
        User user = getByPrincipal(principal);
        if (user.isWithdrawing()) {
            return AccountAccess.WITHDRAWAL_IN_PROGRESS;
        }
        if (!user.isSignupCompleted()) {
            return AccountAccess.SIGNUP_NOT_COMPLETED;
        }
        return termsService.isAgreed(user) ? AccountAccess.OK : AccountAccess.TERMS_NOT_AGREED;
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(String principal) {
        User user = getByPrincipal(principal);
        return CurrentUserResponse.from(user, organizationOf(user), termsService.isAgreed(user));
    }

    private Organization organizationOf(User user) {
        if (!user.isOrganization() || user.getOrganizationId() == null) {
            return null;
        }
        return organizationRepository.findById(user.getOrganizationId()).orElse(null);
    }

    @Transactional(readOnly = true)
    public Long getOrganizationIdOf(String principal) {
        User user = getByPrincipal(principal);
        if (!user.isOrganization() || user.getOrganizationId() == null) {
            throw new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);
        }
        return user.getOrganizationId();
    }

    @Transactional(readOnly = true)
    public Long getParentIdOf(String principal) {
        User user = getByPrincipal(principal);
        if (!user.isParent()) {
            throw new UserException(UserErrorCode.PARENT_REQUIRED);
        }
        return user.getId();
    }

    @Transactional(readOnly = true)
    public Long getUserIdOf(String principal) {
        return getByPrincipal(principal).getId();
    }

    @Transactional
    public Long lockParentForLink(String principal) {
        User user = userRepository.findByIdForShare(parseUserId(principal))
                .orElseThrow(() -> new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));
        requireUsable(user);
        if (!user.isParent()) {
            throw new UserException(UserErrorCode.PARENT_REQUIRED);
        }
        return user.getId();
    }

    private User lockActive(String principal) {
        long id = parseUserId(principal);
        User user = userRepository.findActiveByIdForUpdate(id).orElseThrow(() -> missingOrWithdrawn(id));
        requireUsable(user);
        return user;
    }

    private static void requireUsable(User user) {
        if (user.isDeleted()) {
            throw new UserException(UserErrorCode.ACCOUNT_WITHDRAWN);
        }
        if (user.isWithdrawing()) {
            throw new UserException(UserErrorCode.WITHDRAWAL_IN_PROGRESS);
        }
    }

    private User getByPrincipal(String principal) {
        long id = parseUserId(principal);
        return userRepository.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> missingOrWithdrawn(id));
    }

    private UserException missingOrWithdrawn(long id) {
        boolean withdrawn = userRepository.findById(id).map(User::isDeleted).orElse(false);
        return new UserException(withdrawn ? UserErrorCode.ACCOUNT_WITHDRAWN : UserErrorCode.SESSION_USER_NOT_FOUND);
    }

    public static long parseUserId(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new UserException(UserErrorCode.SESSION_USER_NOT_FOUND);
        }
        try {
            return Long.parseLong(principal);
        } catch (NumberFormatException e) {
            throw new UserException(UserErrorCode.SESSION_USER_NOT_FOUND);
        }
    }

    private Organization createOrganization(SignupRequest request) {
        if (organizationRepository.existsByBusinessNumber(request.businessNumber())) {
            throw new OrganizationException(OrganizationErrorCode.DUPLICATE_BUSINESS_NUMBER);
        }
        Organization organization = Organization.of(
                request.organizationName(), request.organizationType(), request.businessNumber());
        organization.assignCode(organizationCodeIssuer.unusedCode());
        try {
            return organizationRepository.saveAndFlush(organization);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isViolationOf(e, Organization.UK_BUSINESS_NUMBER)) {
                throw new OrganizationException(OrganizationErrorCode.DUPLICATE_BUSINESS_NUMBER);
            }
            if (ConstraintViolations.isViolationOf(e, Organization.UK_CODE)) {
                throw new OrganizationException(OrganizationErrorCode.INSTITUTION_CODE_ISSUE_FAILED);
            }
            throw e;
        }
    }
}
