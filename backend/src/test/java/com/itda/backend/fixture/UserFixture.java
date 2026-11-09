package com.itda.backend.fixture;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;

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
}
