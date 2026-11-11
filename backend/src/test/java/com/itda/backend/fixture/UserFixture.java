package com.itda.backend.fixture;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;
import com.itda.backend.repository.user.UserRepository;

public final class UserFixture {

    private UserFixture() {
    }

    public static User organizationUser(String kakaoId, Long organizationId) {
        User user = User.pending(kakaoId);
        user.completeSignup(UserRole.ORGANIZATION, organizationId);
        return user;
    }

    public static User parent(String kakaoId) {
        User user = User.pending(kakaoId);
        user.completeSignup(UserRole.PARENT, null);
        return user;
    }

    public static User withdrawn(User saved) {
        LocalDateTime now = LocalDateTime.now();
        saved.requestWithdrawal(now);
        saved.markKakaoUnlinked();
        saved.completeWithdrawal(now);
        return saved;
    }

    public static User legacyDeleted(User user) {
        ReflectionTestUtils.setField(user, "deletedAt", LocalDateTime.now());
        return user;
    }

    /** 카카오 회원번호로 찾는다. 운영 코드는 잠금 조회만 쓰므로 테스트에서만 쓰는 조회를 여기 둔다. */
    public static Optional<User> findByKakaoId(UserRepository userRepository, String kakaoId) {
        return userRepository.findAll().stream().filter(user -> kakaoId.equals(user.getKakaoId())).findFirst();
    }
}
