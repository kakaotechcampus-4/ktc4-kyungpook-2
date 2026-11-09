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

import lombok.RequiredArgsConstructor;

/**
 * 회원 등록·조회 규칙. 카카오는 신원 확인 수단일 뿐이고 역할·소속의 기준은 이 서비스다.
 *
 * <p>OAuth2LoginSuccessHandler 는 Spring Security 필터 안에 있는 웹 어댑터라
 * 저장소를 직접 부르지 않고 여기를 거친다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationCodeIssuer organizationCodeIssuer;
    private final LoginUserTransactionService loginUserTransactionService;
    private final TermsService termsService;

    /**
     * 카카오 로그인 성공 시 회원을 찾거나 만든다.
     *
     * <p>새 회원은 역할 없이(가입 미완료) 만든다. 역할과 소속은 가입 API 에서 사용자가 정한다.
     * 로그인 진입 경로로 역할을 추측하지 않는다.
     *
     * <p>탈퇴를 마친 회원은 카카오 회원번호를 지웠으므로 찾지 못하고 새 회원이 된다 — 가입 · 약관 동의를 처음부터 한다.
     * 탈퇴를 처리하는 중인 회원은 그대로 돌려준다(새로 만들지도, 이름을 바꾸지도 않는다). 이미 있는 회원의 역할은
     * 로그인으로 바뀌지 않는다.
     *
     * <p>동시 최초 로그인에서 카카오 ID 중복이 발생하면 롤백 후 새 트랜잭션에서
     * 먼저 등록된 회원을 다시 찾는다. 다른 제약 위반은 숨기지 않는다.
     */
    public User findOrCreateByKakaoId(String kakaoId) {
        return findOrCreateByKakaoId(kakaoId, null);
    }

    /**
     * @param kakaoVerifiedAt 카카오가 이 회원의 연결을 확인하기 전 시각(사용자 정보를 묻기 직전). 이보다 먼저 받은 연결 해제
     *                        웹훅 알림은 지운다 — 그 뒤 다시 연결된 것이다. null 이면(개발용 로그인) 알림을 건드리지 않는다
     */
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

    /**
     * 회원가입 — 카카오 로그인만 한 회원의 역할(과 기관이면 소속 기관)을 확정한다.
     *
     * <p>기관 담당자는 입력한 정보로 기관을 새로 만들고 거기에 소속된다(1기관 1계정).
     * 보호자는 역할만 정한다. 아이 연결은 별도 도메인이다.
     *
     * <p>회원 행을 잠그고 시작한다. 같은 회원의 요청이 겹쳐도 두 번째 요청은 첫 번째가 끝난 뒤
     * 가입 완료 상태를 보고 409 로 끝난다.
     */
    @Transactional
    public CurrentUserResponse completeSignup(String principal, SignupRequest request) {
        User user = lockActive(principal);
        if (user.isSignupCompleted()) {
            throw new UserException(UserErrorCode.ALREADY_SIGNED_UP);
        }

        // 막 가입한 회원은 동의 기록이 없어 termsAgreed 가 false 다. 프론트가 이 값을 보고 약관 화면으로 간다.
        if (request.isOrganizationSignup()) {
            Organization organization = createOrganization(request);
            user.completeSignup(UserRole.ORGANIZATION, organization.getId());
            return CurrentUserResponse.from(user, organization, termsService.isAgreed(user));
        }
        user.completeSignup(UserRole.PARENT, null);
        return CurrentUserResponse.from(user, null, termsService.isAgreed(user));
    }

    /**
     * 서비스 약관 동의. 회원 행을 잠그고 시작한다 — 같은 회원의 요청이 겹쳐도 두 번째는 첫 번째가 남긴
     * 줄을 보고 아무것도 넣지 않는다(TermsService.agree).
     */
    @Transactional
    public TermsAgreementResponse agreeTerms(String principal, List<TermKey> agreed, Map<TermKey, String> versions) {
        User user = lockActive(principal);
        termsService.agree(user, agreed, versions);
        return new TermsAgreementResponse(true);
    }

    /**
     * 가입 미완료 차단 필터가 쓴다. 회원을 한 번 읽고, 가입을 마쳤으면 약관 동의 기록을 한 번 더 읽는다.
     *
     * <p>없는 회원은 SESSION_USER_NOT_FOUND, 탈퇴한 회원은 ACCOUNT_WITHDRAWN 예외로 차단한다. 탈퇴를 처리하는 중인
     * 회원은 가입 · 약관보다 먼저 본다.
     */
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

    /**
     * 기관 회원만 기관을 찾는다. 보호자 · 가입 미완료는 조회하지 않는다.
     *
     * <p>기관 행이 없어도 예외로 막지 않고 기관명만 뺀다. 라우트 가드가 화면마다 부르는 응답이라, 여기서 실패하면
     * 화면 표시용 이름 하나 때문에 그 계정이 앱을 전혀 쓰지 못한다.
     */
    private Organization organizationOf(User user) {
        if (!user.isOrganization() || user.getOrganizationId() == null) {
            return null;
        }
        return organizationRepository.findById(user.getOrganizationId()).orElse(null);
    }

    /**
     * 기관 전용 API 가 쓸 기관 식별자. 소속이 없으면 권한 문제로 막는다.
     */
    @Transactional(readOnly = true)
    public Long getOrganizationIdOf(String principal) {
        User user = getByPrincipal(principal);
        if (!user.isOrganization() || user.getOrganizationId() == null) {
            throw new UserException(UserErrorCode.ORGANIZATION_NOT_ASSIGNED);
        }
        return user.getOrganizationId();
    }

    /**
     * 보호자 전용 API 가 쓸 보호자 식별자. 기관 담당자는 권한 문제로 막는다.
     */
    @Transactional(readOnly = true)
    public Long getParentIdOf(String principal) {
        User user = getByPrincipal(principal);
        if (!user.isParent()) {
            throw new UserException(UserErrorCode.PARENT_REQUIRED);
        }
        return user.getId();
    }

    /**
     * 탈퇴하지 않은 회원의 id. 역할은 보지 않는다 — 누가 한 일인지 기록할 때 쓴다(예: 동의를 끈 기관 담당자).
     */
    @Transactional(readOnly = true)
    public Long getUserIdOf(String principal) {
        return getByPrincipal(principal).getId();
    }

    /**
     * 초대 수락용 보호자 식별자. 회원 행을 FOR SHARE 로 잠가 탈퇴 정리와 줄을 세운다 — 수락이 먼저 끝나면 탈퇴가 새 연결까지
     * 보고 끊고, 탈퇴가 먼저 끝나면 수락은 401 이다. 같은 회원의 수락끼리는 서로 막지 않는다. 잠금 순서의 맨 앞이다
     * (DB 스키마 §3.3). 부르는 쪽 트랜잭션에 합류해야 잠금이 수락이 끝날 때까지 남는다.
     */
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

    /** 가입 · 약관 동의용. 회원 행을 잠근 뒤 탈퇴 여부와 진행 여부를 다시 본다. */
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
        // 탈퇴한 사용자의 출입증은 아직 만료 전이어도 인정하지 않는다. 없는 회원과 코드를 나눠, 탈퇴 안내 화면이
        // "탈퇴가 끝났다" 를 로그인 만료와 구별하게 한다.
        long id = parseUserId(principal);
        return userRepository.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> missingOrWithdrawn(id));
    }

    /** 활성 회원을 찾지 못했을 때. 탈퇴한 행이 남아 있으면 ACCOUNT_WITHDRAWN, 아예 없으면 SESSION_USER_NOT_FOUND. */
    private UserException missingOrWithdrawn(long id) {
        boolean withdrawn = userRepository.findById(id).map(User::isDeleted).orElse(false);
        return new UserException(withdrawn ? UserErrorCode.ACCOUNT_WITHDRAWN : UserErrorCode.SESSION_USER_NOT_FOUND);
    }

    /**
     * principal 은 JwtAuthenticationFilter 가 심은 내부 userId 문자열이다.
     *
     * <p>숫자가 아닌 값도 올 수 있다 — subject 를 userId 로 바꾸기 전에 발급된 토큰에는
     * 카카오 회원번호가 들어 있다. 그때 NumberFormatException 이 새어나가면 catch-all 이
     * 잡아 500 이 되므로, 여기서 인증 실패로 바꾼다.
     */
    static long parseUserId(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new UserException(UserErrorCode.SESSION_USER_NOT_FOUND);
        }
        try {
            return Long.parseLong(principal);
        } catch (NumberFormatException e) {
            throw new UserException(UserErrorCode.SESSION_USER_NOT_FOUND);
        }
    }

    /**
     * 사업자등록번호가 이미 있으면 409. 먼저 조회로 거르고, 두 사람이 동시에 같은 번호로 가입해
     * 조회를 함께 통과한 경우는 유니크 제약이 막는다 — 그 예외도 500 이 아니라 같은 409 로 바꾼다.
     * saveAndFlush 로 즉시 INSERT 해야 제약 위반이 여기서 드러나고, 기관 id 도 받을 수 있다.
     */
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
            // 사업자등록번호와 기관 코드가 둘 다 유니크라 어느 쪽에 걸렸는지 가려야 한다.
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
