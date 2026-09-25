package com.itda.backend.fixture;

import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;

/**
 * 가입을 마친 회원을 만든다. 운영 코드에는 "처음부터 역할이 있는 회원" 을 만드는 길이 없다 —
 * 카카오 로그인으로 가입 미완료 회원이 생기고, 가입 API 가 역할을 정한다. 테스트도 같은 순서를 밟는다.
 */
public final class UserFixture {

    private UserFixture() {
    }

    public static User organizationUser(String kakaoId, String name, Long organizationId) {
        User user = User.pending(kakaoId, name);
        user.completeSignup(UserRole.ORGANIZATION, organizationId);
        return user;
    }

    public static User parent(String kakaoId, String name) {
        User user = User.pending(kakaoId, name);
        user.completeSignup(UserRole.PARENT, null);
        return user;
    }
}
