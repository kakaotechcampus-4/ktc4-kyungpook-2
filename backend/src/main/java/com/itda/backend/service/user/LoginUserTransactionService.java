package com.itda.backend.service.user;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.User;
import com.itda.backend.repository.user.UserRepository;

import lombok.RequiredArgsConstructor;

/** 로그인 저장 작업. 제약 위반 후 조회가 실패한 트랜잭션을 재사용하지 않도록 분리한다. */
@Service
@RequiredArgsConstructor
public class LoginUserTransactionService {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User findOrCreate(String kakaoId) {
        return findExistingLocked(kakaoId)
                .orElseGet(() -> userRepository.saveAndFlush(User.pending(kakaoId)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<User> findExisting(String kakaoId) {
        return findExistingLocked(kakaoId);
    }

    /**
     * 회원 행을 잠그고 찾는다. 탈퇴 실행기도 같은 행을 잠그므로 서로의 변경을 덮어쓰지 않는다.
     *
     * <ul>
     *   <li>탈퇴를 처리하는 중이면 그대로 돌려준다. 새 회원을 만들지 않는다 — 출입증을 받아 {@code /auth/me} 로 진행 상태를
     *       보게 한다.</li>
     *   <li>연결 해제 웹훅 알림이 남아 있으면 지운다. 카카오 로그인에 성공했다는 것은 지금 연결돼 있다는 뜻이다 — 그 알림을
     *       확인하던 실행기가 이전 조회 결과(끊김)로 이 회원을 탈퇴시키지 않는다(같은 행 잠금 아래에서 지우므로, 실행기는 알림이
     *       없어진 것을 보고 결과를 쓰지 않는다)</li>
     *   <li>옛 방식으로 탈퇴 표시만 되고 카카오 회원번호가 남은 행이면 식별정보를 지우고 비워 둔다. 부르는 쪽이 새 회원을
     *       만든다 — 되살리지 않는다. 지운 변경을 먼저 내보내야 같은 회원번호의 새 행이 유니크 제약에 걸리지 않는다.</li>
     * </ul>
     */
    private Optional<User> findExistingLocked(String kakaoId) {
        return userRepository.findByKakaoIdForUpdate(kakaoId).flatMap(user -> {
            if (user.isDeleted()) {
                user.eraseLegacyIdentity();
                userRepository.flush();
                return Optional.empty();
            }
            if (user.hasKakaoUnlinkReport()) {
                user.dismissKakaoUnlinkReport();
            }
            return Optional.of(user);
        });
    }
}
