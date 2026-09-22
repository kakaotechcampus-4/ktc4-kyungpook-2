package com.itda.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.dto.response.CurrentUserResponse;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 등록·조회 규칙. 카카오는 신원 확인 수단일 뿐이고 역할·소속의 기준은 이 서비스다.
 *
 * <p>OAuth2LoginSuccessHandler 는 Spring Security 필터 안에 있는 웹 어댑터라
 * 저장소를 직접 부르지 않고 여기를 거친다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * 카카오 로그인 성공 시 회원을 찾거나 만든다.
     *
     * <p><b>이미 있는 회원의 역할은 바꾸지 않는다.</b> 기관으로 가입한 사람이 나중에 초대
     * 링크를 눌렀다고 보호자가 되어버리면 안 된다. 역할을 바꾸는 절차가 필요해지면
     * 초대·아이 연결 도메인에서 별도로 설계한다.
     *
     * <p>같은 카카오 계정으로 동시에 첫 로그인이 겹치면 uk_users_kakao_id 에 걸려
     * 한쪽이 예외로 끝난다. 호출하는 성공 핸들러가 로그인 실패 화면으로 돌려보내고,
     * 사용자가 다시 누르면 통과한다. 지금 규모에서는 재시도 장치를 두지 않는다.
     */
    @Transactional
    public User findOrCreateByKakaoId(String kakaoId, String nickname, UserRole role) {
        return userRepository.findByKakaoId(kakaoId)
                .map(existing -> {
                    existing.updateName(nickname);
                    return existing;
                })
                .orElseGet(() -> userRepository.save(
                        User.of(kakaoId, nickname, role, resolveOrganizationIdFor(role))));
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

    /**
     * principal 은 JwtAuthenticationFilter 가 심은 내부 userId 문자열이다.
     *
     * <p>숫자가 아닌 값도 올 수 있다 — subject 를 userId 로 바꾸기 전에 발급된 토큰에는
     * 카카오 회원번호가 들어 있다. 그때 NumberFormatException 이 새어나가면 catch-all 이
     * 잡아 500 이 되므로, 여기서 인증 실패로 바꾼다.
     */
    private User getByPrincipal(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new UserException(UserErrorCode.SESSION_USER_NOT_FOUND);
        }
        long userId;
        try {
            userId = Long.parseLong(principal);
        } catch (NumberFormatException e) {
            throw new UserException(UserErrorCode.SESSION_USER_NOT_FOUND);
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));
    }

    /**
     * 기관 선택 화면이 없어서 서버가 정한다. 시드된 기관 중 가장 먼저 만들어진 것을 쓴다.
     *
     * <p>FK 제약이 없으므로 여기가 정합성을 지키는 지점이다 — 실제로 존재하는 기관의 id 만 넣는다.
     *
     * <p><b>기관이 하나도 없으면 사용자를 만들지 않고 실패시킨다.</b> 소속 없이 만들어 두면
     * 두 가지가 한꺼번에 망가진다. {@code /auth/me} 가 role 은 org 인데 institutionId 는 없는
     * 응답을 내보내 계약을 어기고("org 일 때만 포함" 이지 "org 면 포함" 이 아니게 된다),
     * 역할은 나중에 다시 로그인해도 바뀌지 않으므로 그렇게 만들어진 사용자는 시드를 고친 뒤에도
     * 영구히 고장난 채로 남는다. 로그인이 실패하면 원인이 즉시 드러나고 고치면 바로 정상이 된다.
     */
    private Long resolveOrganizationIdFor(UserRole role) {
        if (role != UserRole.ORGANIZATION) {
            return null;
        }
        // map(getId).orElseThrow 로 쓰면 "기관이 없다" 와 "기관은 있는데 id 가 null 이다" 가
        // 구분되지 않는다. 앞의 것만 설정 문제이므로 조회와 id 꺼내기를 나눈다.
        Organization organization = organizationRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "배정할 기관이 없어 기관 담당자를 만들 수 없습니다. "
                                + "app.seed.organizations.enabled 설정과 organization 테이블을 확인하세요."));
        return organization.getId();
    }
}
