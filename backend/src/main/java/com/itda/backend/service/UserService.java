package com.itda.backend.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.dto.request.SignupRequest;
import com.itda.backend.dto.response.CurrentUserResponse;
import com.itda.backend.exception.OrganizationErrorCode;
import com.itda.backend.exception.OrganizationException;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

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

    /**
     * 카카오 로그인 성공 시 회원을 찾거나 만든다.
     *
     * <p>새 회원은 역할 없이(가입 미완료) 만든다. 역할과 소속은 가입 API 에서 사용자가 정한다.
     * 로그인 진입 경로로 역할을 추측하지 않는다.
     *
     * <p>탈퇴한 회원이면 새로 만들지 않고 되살린다. kakao_id 유니크 제약 때문에 새로 넣을 수 없다.
     * 역할과 소속도 탈퇴 전 값을 그대로 쓴다. 이미 있는 회원의 역할은 로그인으로 바뀌지 않는다.
     *
     * <p>같은 카카오 계정으로 동시에 첫 로그인이 겹치면 uk_users_kakao_id 에 걸려
     * 한쪽이 예외로 끝난다. 호출하는 성공 핸들러가 로그인 실패 화면으로 돌려보내고,
     * 사용자가 다시 누르면 통과한다. 지금 규모에서는 재시도 장치를 두지 않는다.
     */
    @Transactional
    public User findOrCreateByKakaoId(String kakaoId, String nickname) {
        return userRepository.findByKakaoId(kakaoId)
                .map(existing -> {
                    if (existing.isDeleted()) {
                        existing.restore();
                    }
                    existing.updateName(nickname);
                    return existing;
                })
                .orElseGet(() -> userRepository.save(User.pending(kakaoId, nickname)));
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
        User user = userRepository.findActiveByIdForUpdate(parseUserId(principal))
                .orElseThrow(() -> new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));
        if (user.isSignupCompleted()) {
            throw new UserException(UserErrorCode.ALREADY_SIGNED_UP);
        }

        if (request.isOrganizationSignup()) {
            Organization organization = createOrganization(request);
            user.completeSignup(UserRole.ORGANIZATION, organization.getId());
        } else {
            user.completeSignup(UserRole.PARENT, null);
        }
        return CurrentUserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(String principal) {
        return CurrentUserResponse.from(getByPrincipal(principal));
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

    private User getByPrincipal(String principal) {
        // 탈퇴한 사용자의 출입증은 아직 만료 전이어도 인정하지 않는다.
        return userRepository.findByIdAndDeletedAtIsNull(parseUserId(principal))
                .orElseThrow(() -> new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));
    }

    /**
     * principal 은 JwtAuthenticationFilter 가 심은 내부 userId 문자열이다.
     *
     * <p>숫자가 아닌 값도 올 수 있다 — subject 를 userId 로 바꾸기 전에 발급된 토큰에는
     * 카카오 회원번호가 들어 있다. 그때 NumberFormatException 이 새어나가면 catch-all 이
     * 잡아 500 이 되므로, 여기서 인증 실패로 바꾼다.
     */
    private long parseUserId(String principal) {
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
        try {
            return organizationRepository.saveAndFlush(Organization.of(
                    request.organizationName(), request.organizationType(), request.businessNumber()));
        } catch (DataIntegrityViolationException e) {
            throw new OrganizationException(OrganizationErrorCode.DUPLICATE_BUSINESS_NUMBER);
        }
    }
}
