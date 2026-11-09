package com.itda.backend.service.user;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.User;
import com.itda.backend.repository.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 로그인 저장 작업. 제약 위반 후 조회가 실패한 트랜잭션을 재사용하지 않도록 분리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginUserTransactionService {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User findOrCreate(String kakaoId, LocalDateTime kakaoVerifiedAt) {
        return findExistingLocked(kakaoId, kakaoVerifiedAt)
                .orElseGet(() -> userRepository.saveAndFlush(User.pending(kakaoId)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<User> findExisting(String kakaoId, LocalDateTime kakaoVerifiedAt) {
        return findExistingLocked(kakaoId, kakaoVerifiedAt);
    }

    /**
     * 회원 행을 잠그고 찾는다. 탈퇴 실행기도 같은 행을 잠그므로 서로의 변경을 덮어쓰지 않는다.
     *
     * <ul>
     *   <li>탈퇴를 처리하는 중이면 그대로 돌려준다. 새 회원을 만들지 않는다 — 출입증을 받아 {@code /auth/me} 로 진행 상태를
     *       보게 한다.</li>
     *   <li>연결 해제 웹훅 알림이 남아 있고 그 알림을 <b>카카오가 이 회원의 연결을 확인하기 전에</b> 받았으면 지운다 — 그 뒤
     *       다시 연결된 것이다. 그 알림을 확인하던 실행기가 이전 조회 결과(끊김)로 이 회원을 탈퇴시키지 않는다(같은 행 잠금 아래에서
     *       지우므로, 실행기는 알림이 없어진 것을 보고 결과를 쓰지 않는다). 연결 확인 뒤에 받은 알림은 남긴다 — 사용자 정보 조회와
     *       이 처리 사이에 끊었을 수 있다</li>
     *   <li>웹훅으로 시작된 탈퇴(사용자가 신청하지 않았다)가 아직 정리 전이고, 그 웹훅을 <b>카카오가 이 회원의 연결을 확인하기
     *       전에</b> 받았으면 탈퇴를 취소한다 — 웹훅 확인 뒤 다시 연결한 것이다. 확인 결과(끊김)는 조회한 순간의 상태일 뿐이다.
     *       정리도 같은 행을 잠그고 탈퇴 단계를 다시 보므로, 취소한 뒤에는 아이 연결 · 동의를 지우지 않는다</li>
     *   <li>옛 방식으로 탈퇴 표시만 되고 카카오 회원번호가 남은 행이면 식별정보를 지우고 비워 둔다. 부르는 쪽이 새 회원을
     *       만든다 — 되살리지 않는다. 지운 변경을 먼저 내보내야 같은 회원번호의 새 행이 유니크 제약에 걸리지 않는다.</li>
     * </ul>
     */
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
            }
            return Optional.of(user);
        });
    }
}
