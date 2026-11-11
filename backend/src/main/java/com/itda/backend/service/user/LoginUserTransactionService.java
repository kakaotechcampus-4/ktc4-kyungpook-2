package com.itda.backend.service.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.User;
import com.itda.backend.repository.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginUserTransactionService {

    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User findOrCreate(String kakaoId, LocalDateTime kakaoVerifiedAt) {
        return findExistingLocked(kakaoId, kakaoVerifiedAt)
                .orElseGet(() -> userRepository.saveAndFlush(User.pending(kakaoId)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<User> findExisting(String kakaoId, LocalDateTime kakaoVerifiedAt) {
        return findExistingLocked(kakaoId, kakaoVerifiedAt);
    }

    private Optional<User> findExistingLocked(String kakaoId, LocalDateTime kakaoVerifiedAt) {
        return userRepository.findByKakaoIdForUpdate(kakaoId).flatMap(user -> {
            if (user.isDeleted()) {
                user.eraseLegacyIdentity();
                userRepository.flush();
                return Optional.empty();
            }
            if (user.hasKakaoUnlinkReport() && kakaoVerifiedAt != null
                    && user.getKakaoUnlinkReportedAt().isBefore(kakaoVerifiedAt)) {
                user.dismissKakaoUnlinkReport();
            }
            if (user.isRelinkedAfterWebhookWithdrawal(kakaoVerifiedAt)) {
                user.cancelWebhookWithdrawal();
                log.info("연결 해제 웹훅으로 시작된 탈퇴를 다시 연결한 로그인이 취소했습니다: userId={}", user.getId());
            } else if (user.isRelinkedBeforeCleanup(kakaoVerifiedAt)) {
                user.reopenKakaoUnlink(LocalDateTime.now(clock));
                log.info("정리 전에 카카오 로그인이 와서 연결 해제를 다시 부릅니다: userId={}", user.getId());
            }
            return Optional.of(user);
        });
    }
}
