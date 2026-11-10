package com.itda.backend.service.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.KakaoUnlinkWebhook;
import com.itda.backend.domain.user.User;
import com.itda.backend.domain.user.WithdrawalStatus;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.repository.user.KakaoUnlinkWebhookRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.ChildGuardianService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalSteps {

    private final UserRepository userRepository;
    private final KakaoUnlinkWebhookRepository webhookRepository;
    private final ChildGuardianService childGuardianService;
    private final Clock clock;

    public record Plan(Kind kind, Long kakaoUserId, boolean firstAttempt, LocalDateTime reportedAt) {

        public enum Kind {
            NOTHING,
            CALL_KAKAO,
            CLEANUP,
            VERIFY_REPORT
        }

        static Plan nothing() {
            return new Plan(Kind.NOTHING, null, false, null);
        }

        static Plan cleanup() {
            return new Plan(Kind.CLEANUP, null, false, null);
        }

        static Plan callKakao(long kakaoUserId, boolean firstAttempt) {
            return new Plan(Kind.CALL_KAKAO, kakaoUserId, firstAttempt, null);
        }

        static Plan verifyReport(long kakaoUserId, LocalDateTime reportedAt) {
            return new Plan(Kind.VERIFY_REPORT, kakaoUserId, false, reportedAt);
        }
    }

    public enum ReportResolution {
        PROCEED,
        PROCEED_WITHOUT_REPORT,
        RETRY_LATER
    }

    public enum Outcome {
        COMPLETED,
        IN_PROGRESS,
        CANCELED
    }

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

    @Transactional
    public Optional<Long> absorbWebhook(long webhookId) {
        KakaoUnlinkWebhook webhook = webhookRepository.findById(webhookId).orElse(null);
        if (webhook == null) {
            return Optional.empty();
        }
        User user = userRepository.findByKakaoIdForUpdateNoWait(webhook.getKakaoId()).orElse(null);
        if (webhookRepository.deleteOne(webhookId) == 0) {
            return Optional.empty();
        }
        return report(user, webhook.getReceivedAt());
    }

    @Transactional
    public Optional<Long> reportUnlinkedByKakao(String kakaoId) {
        return report(userRepository.findByKakaoIdForUpdate(kakaoId).orElse(null), now());
    }

    private Optional<Long> report(User user, LocalDateTime receivedAt) {
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
        user.reportKakaoUnlink(receivedAt);
        return Optional.of(user.getId());
    }

    @Transactional
    public ReportResolution resolveReport(long userId, KakaoLinkStatus status, LocalDateTime reportedAtSeen) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.isDeleted() || !user.hasKakaoUnlinkReport()) {
            return ReportResolution.PROCEED;
        }
        if (!user.getKakaoUnlinkReportedAt().equals(reportedAtSeen)) {
            log.info("확인하는 사이 새 연결 해제 웹훅이 와서 다시 묻습니다: userId={}", userId);
            return ReportResolution.RETRY_LATER;
        }
        switch (status) {
            case NOT_LINKED -> {
                user.confirmKakaoUnlinkReport(now());
                log.info("카카오 쪽에서 연결이 끊긴 것을 확인해 탈퇴를 처리합니다: userId={}", userId);
                return ReportResolution.PROCEED;
            }
            case LINKED -> {
                user.dismissKakaoUnlinkReport();
                log.info("연결 해제 웹훅이 왔지만 지금은 연결돼 있어 지난 연결의 알림으로 봅니다: userId={}", userId);
                return ReportResolution.PROCEED;
            }
            default -> {
                return user.getWithdrawalStatus() == WithdrawalStatus.REQUESTED
                        ? ReportResolution.PROCEED_WITHOUT_REPORT
                        : ReportResolution.RETRY_LATER;
            }
        }
    }

    @Transactional
    public Plan prepare(long userId) {
        return prepare(userId, true);
    }

    @Transactional
    public Plan prepare(long userId, boolean verifyReport) {
        Optional<User> found = userRepository.findByIdForUpdate(userId);
        if (found.isEmpty() || found.get().isDeleted()) {
            return Plan.nothing();
        }
        User user = found.get();
        if (user.getWithdrawalStatus() == WithdrawalStatus.KAKAO_UNLINKED) {
            return Plan.cleanup();
        }
        if (verifyReport && user.hasKakaoUnlinkReport()) {
            Optional<Long> reported = user.kakaoUserId();
            if (reported.isPresent()) {
                return Plan.verifyReport(reported.get(), user.getKakaoUnlinkReportedAt());
            }
            user.dismissKakaoUnlinkReport();
        }
        if (!user.isWithdrawing()) {
            return Plan.nothing();
        }
        Optional<Long> kakaoUserId = user.kakaoUserId();
        if (kakaoUserId.isEmpty()) {
            user.markKakaoUnlinked();
            return Plan.cleanup();
        }
        LocalDateTime now = now();
        if (!user.isDueForRetry(now)) {
            log.info("카카오 연결 해제 재시도 간격이 지나지 않아 이번에는 부르지 않습니다: userId={} 횟수={}",
                    userId, user.kakaoUnlinkFailureCount());
            return Plan.nothing();
        }
        boolean first = user.markKakaoUnlinkAttempt(now);
        return Plan.callKakao(kakaoUserId.get(), first);
    }

    @Transactional
    public boolean record(long userId, KakaoUnlinkResult result, boolean firstAttempt) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user != null && user.getWithdrawalStatus() == WithdrawalStatus.KAKAO_UNLINKED) {
            return true;
        }
        if (user == null || user.getWithdrawalStatus() != WithdrawalStatus.REQUESTED) {
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
                if (firstAttempt && !user.isOrganization()) {
                    user.cancelWithdrawal();
                    log.warn("카카오 연결 해제 첫 호출이 실패해 탈퇴 접수를 취소했습니다: userId={}", userId);
                } else {
                    user.countKakaoUnlinkFailure();
                    log.error("이전 호출 결과를 모르는 탈퇴에서 카카오가 거절했습니다. 간격을 늘려 다시 시도합니다: userId={} 횟수={}",
                            userId, user.kakaoUnlinkFailureCount());
                }
                return false;
            }
            default -> {
                user.countKakaoUnlinkFailure();
                log.warn("카카오 연결 해제 결과를 몰라 간격을 늘려 다시 시도합니다: userId={} 횟수={}",
                        userId, user.kakaoUnlinkFailureCount());
                return false;
            }
        }
    }

    @Transactional
    public boolean cleanup(long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getWithdrawalStatus() != WithdrawalStatus.KAKAO_UNLINKED) {
            return false;
        }
        childGuardianService.unlinkAllForWithdrawal(user.getId());
        user.completeWithdrawal(now());
        return true;
    }

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
    public List<Long> dueIds() {
        LocalDateTime now = now();
        return userRepository.findAllById(userRepository.findWithdrawingIds()).stream()
                .filter(user -> user.isDueForRetry(now))
                .map(User::getId)
                .sorted()
                .toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
