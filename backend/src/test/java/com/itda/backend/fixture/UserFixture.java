package com.itda.backend.fixture;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.UserRole;

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

    /**
     * 탈퇴를 끝까지 마친다(접수 → 카카오 해제 확정 → 정리). 저장된 회원이어야 한다 — 카카오 회원번호 자리에 내부 id 를 쓴다.
     * 아이 연결 정리는 하지 않는다. 연결까지 보는 테스트는 실제 실행기를 쓴다.
     */
    public static User withdrawn(User saved) {
        LocalDateTime now = LocalDateTime.now();
        saved.requestWithdrawal(now);
        saved.markKakaoUnlinked();
        saved.completeWithdrawal(now);
        return saved;
    }

    /** 옛 방식 탈퇴 행 — 카카오 회원번호와 이름을 남긴 채 탈퇴 표시만 했다. 지금 코드로는 만들 수 없어 필드를 직접 채운다. */
    public static User legacyDeleted(User user) {
        ReflectionTestUtils.setField(user, "deletedAt", LocalDateTime.now());
        return user;
    }
}
