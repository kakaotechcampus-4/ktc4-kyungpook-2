package com.itda.backend.service.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.WithdrawalStatus;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.ChildGuardianService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴의 짧은 트랜잭션들. 단계마다 따로 커밋한다 — 카카오를 부르는 동안에는 회원 행을 잠그지 않아, 그동안 로그인이나
 * {@code /auth/me} 가 기다리지 않는다. 단계 사이의 순서는 {@link WithdrawalExecutor} 가 탈퇴 잠금을 쥔 채로 지킨다.
 *
 * <p>모든 단계가 회원 행을 먼저 잠근다(잠금 순서의 맨 앞, DB 스키마 §3.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalSteps {

    private final UserRepository userRepository;
    private final ChildGuardianService childGuardianService;
    private final Clock clock;

    /** 실행기가 이번에 할 일. */
    public record Plan(Kind kind, Long kakaoUserId, boolean firstAttempt) {

        public enum Kind {
            /** 진행 중인 탈퇴가 없다(이미 끝났거나 취소됐다). */
            NOTHING,
            /** 카카오를 부른다. */
            CALL_KAKAO,
            /** 카카오 해제가 확정됐다. DB 정리만 한다. */
            CLEANUP
        }

        static Plan nothing() {
            return new Plan(Kind.NOTHING, null, false);
        }

        static Plan cleanup() {
            return new Plan(Kind.CLEANUP, null, false);
        }

        static Plan callKakao(long kakaoUserId, boolean firstAttempt) {
            return new Plan(Kind.CALL_KAKAO, kakaoUserId, firstAttempt);
        }
    }

    /** 탈퇴 API 가 기다린 뒤 본 결과. */
    public enum Outcome {
        /** {@code deleted_at} 을 확인했다. */
        COMPLETED,
        /** 아직 처리 중이다. */
        IN_PROGRESS,
        /** 첫 호출이 분명히 실패해 접수가 취소됐다. */
        CANCELED
    }

    /**
     * 탈퇴를 접수한다. 카카오를 부르기 전에 모든 거절을 끝낸다 — 기관 담당자 403, 이미 탈퇴 401, 이미 접수 409.
     * 다시 신청하는 것이면 이전 호출 시각을 지운다(새로운 첫 호출이다).
     */
    @Transactional
    public long accept(long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.SESSION_USER_NOT_FOUND));
        if (user.isDeleted()) {
            throw new UserException(UserErrorCode.ACCOUNT_WITHDRAWN);
        }
        if (user.isWithdrawing()) {
            throw new UserException(UserErrorCode.WITHDRAWAL_IN_PROGRESS);
        }
        if (user.isOrganization()) {
            throw new UserException(UserErrorCode.ORGANIZATION_WITHDRAWAL_NOT_SUPPORTED);
        }
        user.requestWithdrawal(now());
        return user.getId();
    }

    /**
     * 카카오 쪽에서 먼저 연결을 끊었다(연결 해제 웹훅). 카카오 연결은 이미 끊겼으므로 카카오를 부르지 않고 바로 DB 정리로 넘긴다 —
     * 다시 부르면, 그 사이 사용자가 다시 로그인해 생긴 새 연결을 끊을 수 있다.
     *
     * <ul>
     *   <li>없는 회원 · 탈퇴를 마친 회원 → 할 일 없음. 옛 방식 탈퇴 행이면 남은 식별정보를 지운다</li>
     *   <li>기관 담당자 → 할 일 없음. 기관 탈퇴는 지원하지 않는다 — 계정은 남고, 다시 카카오로 로그인하면 연결된다</li>
     *   <li>이미 탈퇴를 처리하는 중 → 실행기에 다시 세운다(카카오를 부르면 이미 끊김 {@code -101} 로 성공한다)</li>
     *   <li>그 밖(보호자 · 가입 전) → 접수와 함께 {@code KAKAO_UNLINKED}</li>
     * </ul>
     *
     * @return 실행기에 세울 회원 id
     */
    @Transactional
    public Optional<Long> acceptUnlinkedByKakao(String kakaoId) {
        User user = userRepository.findByKakaoIdForUpdate(kakaoId).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        if (user.isDeleted()) {
            user.eraseLegacyIdentity();
            return Optional.empty();
        }
        if (user.isOrganization()) {
            log.warn("기관 담당자의 카카오 연결이 카카오 쪽에서 끊겼습니다. 기관 탈퇴는 지원하지 않아 계정을 남깁니다: userId={}",
                    user.getId());
            return Optional.empty();
        }
        if (!user.isWithdrawing()) {
            user.requestWithdrawal(now());
            user.markKakaoUnlinked();
        }
        return Optional.of(user.getId());
    }

    /**
     * 상태를 다시 읽고 이번에 할 일을 정한다. 카카오를 부를 것이면 <b>부르기 직전에</b> 호출 시각을 남기고 커밋한다 — 그 뒤
     * 프로세스가 죽어도 "이전 호출이 성공했을 수 있다" 가 남는다. 카카오 계정이 아닌 회원(개발용 로그인)은 부르지 않는다.
     */
    @Transactional
    public Plan prepare(long userId) {
        Optional<User> found = userRepository.findByIdForUpdate(userId);
        if (found.isEmpty() || found.get().isDeleted() || !found.get().isWithdrawing()) {
            return Plan.nothing();
        }
        User user = found.get();
        if (user.getWithdrawalStatus() == WithdrawalStatus.KAKAO_UNLINKED) {
            return Plan.cleanup();
        }
        Optional<Long> kakaoUserId = user.kakaoUserId();
        if (kakaoUserId.isEmpty()) {
            user.markKakaoUnlinked();
            return Plan.cleanup();
        }
        boolean first = user.markKakaoUnlinkAttempt(now());
        return Plan.callKakao(kakaoUserId.get(), first);
    }

    /**
     * 카카오 결과를 남긴다.
     *
     * <ul>
     *   <li>끊김(이미 끊김 포함) → {@code KAKAO_UNLINKED}</li>
     *   <li>분명한 오류 + 이 접수의 첫 호출 → 접수 취소. 회원은 그대로 서비스를 쓴다</li>
     *   <li>분명한 오류 + 이전 호출 있음 → 그대로 둔다. 이전 호출이 성공했을 수 있다</li>
     *   <li>모름 → 그대로 둔다. 실행기가 다시 부른다</li>
     * </ul>
     *
     * @return DB 정리로 넘어가도 되는지
     */
    @Transactional
    public boolean record(long userId, KakaoUnlinkResult result, boolean firstAttempt) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getWithdrawalStatus() != WithdrawalStatus.REQUESTED) {
            // 탈퇴 잠금 안이라 생기지 않아야 한다. 생겼다면 남이 바꾼 상태를 덮어쓰지 않는다
            log.error("카카오 결과를 남길 탈퇴 접수가 없습니다: userId={} status={}",
                    userId, user == null ? null : user.getWithdrawalStatus());
            return false;
        }
        switch (result) {
            case UNLINKED -> {
                user.markKakaoUnlinked();
                return true;
            }
            case FAILED -> {
                if (firstAttempt) {
                    user.cancelWithdrawal();
                    log.warn("카카오 연결 해제 첫 호출이 실패해 탈퇴 접수를 취소했습니다: userId={}", userId);
                } else {
                    log.error("이전 호출 결과를 모르는 탈퇴에서 카카오가 거절했습니다. 다시 시도합니다: userId={}", userId);
                }
                return false;
            }
            default -> {
                log.warn("카카오 연결 해제 결과를 몰라 다시 시도합니다: userId={}", userId);
                return false;
            }
        }
    }

    /**
     * DB 정리. 아이 연결을 모두 끊고, 카카오 회원번호와 이름을 지우고, 탈퇴 표시를 한다. 실패하면 전체가 되돌아가고
     * {@code KAKAO_UNLINKED} 로 남아 이 단계만 다시 한다(카카오는 다시 부르지 않는다).
     */
    @Transactional
    public void cleanup(long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getWithdrawalStatus() != WithdrawalStatus.KAKAO_UNLINKED) {
            return;
        }
        // 회원 행을 잠근 뒤에 연결 목록을 읽는다 — 초대 수락은 회원 행을 FOR SHARE 로 잠그고 시작하므로 끼어들지 못한다
        childGuardianService.unlinkAllForWithdrawal(user.getId());
        user.completeWithdrawal(now());
    }

    /** 실행기가 커밋한 결과를 DB 에서 읽는다. 완료는 {@code deleted_at} 을 확인한 경우만이다. */
    @Transactional(readOnly = true)
    public Outcome outcomeOf(long userId) {
        UserRepository.WithdrawalView view = userRepository.findWithdrawalViewById(userId).orElse(null);
        if (view == null) {
            return Outcome.IN_PROGRESS;
        }
        if (view.getDeletedAt() != null) {
            return Outcome.COMPLETED;
        }
        return view.getWithdrawalStatus() != null ? Outcome.IN_PROGRESS : Outcome.CANCELED;
    }

    @Transactional(readOnly = true)
    public List<Long> withdrawingIds() {
        return userRepository.findWithdrawingIds();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
