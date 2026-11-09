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
    private final KakaoUnlinkWebhookRepository webhookRepository;
    private final ChildGuardianService childGuardianService;
    private final Clock clock;

    /** 실행기가 이번에 할 일. */
    /**
     * @param reportedAt {@code VERIFY_REPORT} 일 때 확인할 웹훅 알림의 시각. 확인 결과는 이 알림이 그대로 남아 있을 때만 쓴다
     */
    public record Plan(Kind kind, Long kakaoUserId, boolean firstAttempt, LocalDateTime reportedAt) {

        public enum Kind {
            /** 진행 중인 탈퇴가 없다(이미 끝났거나 취소됐다). */
            NOTHING,
            /** 카카오를 부른다. */
            CALL_KAKAO,
            /** 카카오 해제가 확정됐다. DB 정리만 한다. */
            CLEANUP,
            /** 연결 해제 웹훅 알림이 있다. 카카오에 지금 연결 상태를 먼저 묻는다. */
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

    /** 웹훅 알림 확인 뒤 실행기가 할 일. */
    public enum ReportResolution {
        /** 이어서 처리한다(남은 탈퇴가 있으면 진행). */
        PROCEED,
        /** 이어서 처리하되 이번에는 알림 확인을 건너뛴다 — 연결 상태를 몰랐지만 사용자가 직접 신청한 탈퇴가 기다리고 있다. */
        PROCEED_WITHOUT_REPORT,
        /** 이번에는 그만둔다. 알림을 남겨 두고 나중에 다시 묻는다. */
        RETRY_LATER
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
     * 수신함에 남긴 웹훅 하나를 회원 행으로 옮긴다({@link KakaoUnlinkInbox}). 회원 행을 먼저 잠그고(잠금 순서) 수신함 줄을 지운다 —
     * 지운 줄이 없으면 다른 실행이 먼저 옮긴 것이라 아무것도 바꾸지 않는다. 알림 시각은 웹훅을 받은 시각이다.
     *
     * <p>회원 행은 기다리지 않고 잠근다. 잠겨 있으면 잠금 예외로 끝나고 줄은 남는다 — 한 회원 행 때문에 뒤의 웹훅이 밀리지 않는다.
     *
     * @return 실행기에 세울 회원 id
     */
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

    /** 지금 받은 것으로 알림을 남긴다. 수신함을 거치지 않는 테스트용 입구다. */
    @Transactional
    public Optional<Long> reportUnlinkedByKakao(String kakaoId) {
        return report(userRepository.findByKakaoIdForUpdate(kakaoId).orElse(null), now());
    }

    /**
     * 카카오가 연결 해제를 알렸다(웹훅). 여기서는 알림만 남긴다 — 웹훅에는 해제 시각이 없어, 받는 순간에는 이 이벤트가 지금
     * 연결에 대한 것인지 알 수 없다(사용자가 그 뒤 다시 로그인했거나, 옛 연결의 늦은 웹훅이 같은 카카오 계정으로 재가입한 새 계정에
     * 닿았을 수 있다). 실행기가 카카오에 지금 연결 상태를 물어 정말 끊겨 있을 때만 탈퇴로 넘긴다({@link #resolveReport}).
     *
     * <ul>
     *   <li>없는 회원 · 탈퇴를 마친 회원 → 할 일 없음. 옛 방식 탈퇴 행이면 남은 식별정보를 지운다</li>
     *   <li>기관 담당자 → 할 일 없음. 기관 탈퇴는 지원하지 않는다 — 계정은 남고, 다시 카카오로 로그인하면 연결된다</li>
     *   <li>그 밖(보호자 · 가입 전, 탈퇴 처리 중 포함) → 알림을 남기고 실행기에 세운다</li>
     * </ul>
     *
     * @param user 잠근 회원 행. 없으면 null
     * @return 실행기에 세울 회원 id
     */
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

    /**
     * 연결 해제 웹훅 알림을 카카오의 지금 연결 상태로 확인한다. 회원 행을 잠근 뒤, <b>조회를 시작할 때 본 알림이 그대로 남아 있을
     * 때만</b> 결과를 쓴다.
     * <ul>
     *   <li>알림이 없어졌다 — 그 사이 카카오 로그인에 성공해 알림이 무효가 됐다(LoginUserTransactionService). 조회 결과가 끊김이었어도
     *       그 뒤 다시 연결된 것이다. 아무것도 바꾸지 않는다</li>
     *   <li>알림이 새것으로 바뀌었다 — 그 사이 새 웹훅이 왔다. 이 결과로 새 알림을 지우거나 확정하지 않고, 다시 묻는다</li>
     * </ul>
     * 같은 알림이면:
     * <ul>
     *   <li>끊겨 있음 → 탈퇴를 접수하고(이미 처리 중이면 그대로) 해제를 확정한다. 그 뒤 실행기가 받은 카카오 오류로 접수가
     *       취소되지 않는다({@link #record} 가 이 상태를 덮어쓰지 않는다). 이 알림으로 새로 접수한 탈퇴는 알림 시각을 정리 때까지
     *       남긴다 — 그 사이 다시 연결한 로그인이 탈퇴를 취소한다(LoginUserTransactionService)</li>
     *   <li>연결돼 있음 → 지난 연결의 알림이다. 알림만 지운다 — 사용자가 직접 신청한 탈퇴가 있으면 그대로 이어진다</li>
     *   <li>모름 → 알림을 남겨 두고 나중에 다시 묻는다. 다만 사용자가 직접 신청한 탈퇴가 기다리고 있으면 그것은 연결 해제를 불러
     *       진행한다 — 조회가 계속 실패해도 직접 신청한 탈퇴가 막히지 않게 한다</li>
     * </ul>
     */
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

    /**
     * 상태를 다시 읽고 이번에 할 일을 정한다. 카카오를 부를 것이면 <b>부르기 직전에</b> 호출 시각을 남기고 커밋한다 — 그 뒤
     * 프로세스가 죽어도 "이전 호출이 성공했을 수 있다" 가 남는다. 카카오 계정이 아닌 회원(개발용 로그인)은 부르지 않는다.
     * 재시도 간격({@link User#isDueForRetry})도 여기서 잠금 아래 다시 본다 — 주기 작업은 줄에 세울 때 보지만, 줄에 서 있는 사이
     * 앞선 호출이 결과를 모른 채 끝나면 간격을 건너뛰게 된다.
     */
    @Transactional
    public Plan prepare(long userId) {
        return prepare(userId, true);
    }

    /**
     * @param verifyReport 웹훅 알림이 있으면 먼저 확인할지. 연결 상태를 몰라 이번에는 건너뛰기로 했으면 false
     */
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
            // 카카오 계정이 아닌 회원에게 웹훅이 올 수 없다. 남은 알림은 지운다
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
            // 줄에 서 있던 사이 앞선 호출이 결과를 모른 채 끝났다. 재시도 간격이 지나면 주기 작업이 다시 세운다
            log.info("카카오 연결 해제 재시도 간격이 지나지 않아 이번에는 부르지 않습니다: userId={} 횟수={}",
                    userId, user.kakaoUnlinkFailureCount());
            return Plan.nothing();
        }
        boolean first = user.markKakaoUnlinkAttempt(now);
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
        if (user != null && user.getWithdrawalStatus() == WithdrawalStatus.KAKAO_UNLINKED) {
            // 카카오 호출 중에 연결 해제 웹훅이 와서 해제를 확정했다. 이번 결과가 무엇이든 덮어쓰지 않고 정리로 넘어간다
            return true;
        }
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

    /**
     * DB 정리. 아이 연결을 모두 끊고, 카카오 회원번호를 지우고, 탈퇴 표시를 한다. 실패하면 전체가 되돌아가고
     * {@code KAKAO_UNLINKED} 로 남아 이 단계만 다시 한다(카카오는 다시 부르지 않는다). 회원 행을 잠근 뒤 단계를 다시 보므로,
     * 그 사이 다시 연결한 로그인이 웹훅 탈퇴를 취소했으면 아무것도 지우지 않는다.
     *
     * @return 탈퇴를 마쳤는지
     */
    @Transactional
    public boolean cleanup(long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getWithdrawalStatus() != WithdrawalStatus.KAKAO_UNLINKED) {
            return false;
        }
        // 회원 행을 잠근 뒤에 연결 목록을 읽는다 — 초대 수락은 회원 행을 FOR SHARE 로 잠그고 시작하므로 끼어들지 못한다
        childGuardianService.unlinkAllForWithdrawal(user.getId());
        user.completeWithdrawal(now());
        return true;
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

    /** 주기 작업이 지금 다시 세울 회원 — 진행 중인 탈퇴 · 웹훅 알림 가운데 재시도 간격이 지난 것 ({@link User#isDueForRetry}). */
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
