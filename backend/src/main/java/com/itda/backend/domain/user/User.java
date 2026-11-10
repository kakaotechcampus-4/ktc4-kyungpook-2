package com.itda.backend.domain.user;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_kakao_id", columnNames = "kakao_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends TimestampedEntity {

    public static final String WITHDRAWN_KAKAO_ID_PREFIX = "withdrawn:";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kakao_id", nullable = false, length = 50)
    private String kakaoId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserRole role;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "withdrawal_status", length = 20)
    private WithdrawalStatus withdrawalStatus;

    @Column(name = "withdrawal_requested_at")
    private LocalDateTime withdrawalRequestedAt;

    @Column(name = "kakao_unlink_attempted_at")
    private LocalDateTime kakaoUnlinkAttemptedAt;

    @Column(name = "kakao_unlink_failures")
    private Integer kakaoUnlinkFailures;

    @Column(name = "kakao_unlink_reported_at")
    private LocalDateTime kakaoUnlinkReportedAt;

    @Column(name = "webhook_withdrawal_reported_at")
    private LocalDateTime webhookWithdrawalReportedAt;

    private User(String kakaoId) {
        this.kakaoId = kakaoId;
    }

    public static User pending(String kakaoId) {
        return new User(kakaoId);
    }

    public void completeSignup(UserRole role, Long organizationId) {
        if (isSignupCompleted()) {
            throw new IllegalStateException("이미 가입을 마친 회원입니다.");
        }
        if (role == null) {
            throw new IllegalArgumentException("역할은 필수입니다.");
        }
        if (role == UserRole.PARENT && organizationId != null) {
            throw new IllegalArgumentException("보호자는 소속 기관을 가질 수 없습니다.");
        }
        if (role == UserRole.ORGANIZATION && organizationId == null) {
            throw new IllegalArgumentException("기관 담당자는 소속 기관이 있어야 합니다.");
        }
        this.role = role;
        this.organizationId = organizationId;
    }

    public void requestWithdrawal(LocalDateTime now) {
        if (isDeleted()) {
            throw new IllegalStateException("이미 탈퇴한 회원입니다.");
        }
        if (isWithdrawing()) {
            throw new IllegalStateException("이미 탈퇴를 처리하고 있습니다.");
        }
        if (isOrganization()) {
            throw new IllegalStateException("기관 담당자는 탈퇴할 수 없습니다.");
        }
        markWithdrawalRequested(now);
    }

    public void requestHandoverWithdrawal(LocalDateTime now) {
        if (isDeleted()) {
            throw new IllegalStateException("이미 탈퇴한 회원입니다.");
        }
        if (isWithdrawing()) {
            throw new IllegalStateException("이미 탈퇴를 처리하고 있습니다.");
        }
        if (!isOrganization()) {
            throw new IllegalStateException("기관 담당자만 담당자를 넘길 수 있습니다.");
        }
        markWithdrawalRequested(now);
    }

    private void markWithdrawalRequested(LocalDateTime now) {
        this.withdrawalStatus = WithdrawalStatus.REQUESTED;
        this.withdrawalRequestedAt = now;
        this.kakaoUnlinkAttemptedAt = null;
        this.kakaoUnlinkFailures = null;
        this.webhookWithdrawalReportedAt = null;
    }

    public void countKakaoUnlinkFailure() {
        requireStatus(WithdrawalStatus.REQUESTED);
        this.kakaoUnlinkFailures = kakaoUnlinkFailureCount() + 1;
    }

    public int kakaoUnlinkFailureCount() {
        return this.kakaoUnlinkFailures == null ? 0 : this.kakaoUnlinkFailures;
    }

    public boolean isDueForRetry(LocalDateTime now) {
        if (this.withdrawalStatus != WithdrawalStatus.REQUESTED || this.kakaoUnlinkAttemptedAt == null) {
            return true;
        }
        int failures = kakaoUnlinkFailureCount();
        if (failures == 0) {
            return true;
        }
        long[] minutes = {1, 2, 5, 10, 30, 60};
        long wait = minutes[Math.min(failures, minutes.length) - 1];
        return !now.isBefore(this.kakaoUnlinkAttemptedAt.plusMinutes(wait));
    }

    public void reportKakaoUnlink(LocalDateTime receivedAt) {
        if (isDeleted()) {
            throw new IllegalStateException("이미 탈퇴한 회원입니다.");
        }
        if (this.kakaoUnlinkReportedAt == null || this.kakaoUnlinkReportedAt.isBefore(receivedAt)) {
            this.kakaoUnlinkReportedAt = receivedAt;
        }
    }

    public boolean hasKakaoUnlinkReport() {
        return this.kakaoUnlinkReportedAt != null;
    }

    public void confirmKakaoUnlinkReport(LocalDateTime now) {
        LocalDateTime reportedAt = this.kakaoUnlinkReportedAt;
        this.kakaoUnlinkReportedAt = null;
        if (isOrganization()) {
            return;
        }
        if (!isWithdrawing()) {
            requestWithdrawal(now);
            this.webhookWithdrawalReportedAt = reportedAt;
        }
        if (this.withdrawalStatus == WithdrawalStatus.REQUESTED) {
            markKakaoUnlinked();
        }
    }

    public boolean isRelinkedAfterWebhookWithdrawal(LocalDateTime kakaoVerifiedAt) {
        return !isDeleted() && this.webhookWithdrawalReportedAt != null && kakaoVerifiedAt != null
                && this.webhookWithdrawalReportedAt.isBefore(kakaoVerifiedAt);
    }

    public boolean isRelinkedBeforeCleanup(LocalDateTime kakaoVerifiedAt) {
        return !isDeleted() && kakaoVerifiedAt != null && this.withdrawalStatus == WithdrawalStatus.KAKAO_UNLINKED
                && this.webhookWithdrawalReportedAt == null;
    }

    public void reopenKakaoUnlink(LocalDateTime now) {
        requireStatus(WithdrawalStatus.KAKAO_UNLINKED);
        if (this.webhookWithdrawalReportedAt != null) {
            throw new IllegalStateException("웹훅으로 시작된 탈퇴는 다시 연결하면 취소한다.");
        }
        this.withdrawalStatus = WithdrawalStatus.REQUESTED;
        if (this.kakaoUnlinkAttemptedAt == null) {
            this.kakaoUnlinkAttemptedAt = now;
        }
        this.kakaoUnlinkFailures = null;
    }

    public void cancelWebhookWithdrawal() {
        if (isDeleted() || this.webhookWithdrawalReportedAt == null) {
            throw new IllegalStateException("취소할 웹훅 탈퇴가 없습니다.");
        }
        clearWithdrawal();
    }

    public void dismissKakaoUnlinkReport() {
        this.kakaoUnlinkReportedAt = null;
    }

    public boolean markKakaoUnlinkAttempt(LocalDateTime now) {
        requireStatus(WithdrawalStatus.REQUESTED);
        boolean first = this.kakaoUnlinkAttemptedAt == null;
        this.kakaoUnlinkAttemptedAt = now;
        return first;
    }

    public void markKakaoUnlinked() {
        requireStatus(WithdrawalStatus.REQUESTED);
        this.withdrawalStatus = WithdrawalStatus.KAKAO_UNLINKED;
    }

    public void cancelWithdrawal() {
        requireStatus(WithdrawalStatus.REQUESTED);
        clearWithdrawal();
    }

    public void completeWithdrawal(LocalDateTime now) {
        requireStatus(WithdrawalStatus.KAKAO_UNLINKED);
        eraseIdentity();
        this.deletedAt = now;
        clearWithdrawal();
        clearReport();
    }

    public void eraseLegacyIdentity() {
        if (!isDeleted()) {
            throw new IllegalStateException("탈퇴한 회원만 식별정보를 지울 수 있습니다.");
        }
        eraseIdentity();
    }

    private void eraseIdentity() {
        if (this.id == null) {
            throw new IllegalStateException("저장된 회원만 탈퇴할 수 있습니다.");
        }
        this.kakaoId = WITHDRAWN_KAKAO_ID_PREFIX + this.id;
    }

    private void clearWithdrawal() {
        this.withdrawalStatus = null;
        this.withdrawalRequestedAt = null;
        this.kakaoUnlinkAttemptedAt = null;
        this.kakaoUnlinkFailures = null;
        this.webhookWithdrawalReportedAt = null;
    }

    private void clearReport() {
        this.kakaoUnlinkReportedAt = null;
    }

    private void requireStatus(WithdrawalStatus expected) {
        if (this.withdrawalStatus != expected) {
            throw new IllegalStateException("탈퇴 단계가 " + expected + " 가 아닙니다: " + this.withdrawalStatus);
        }
    }

    public Optional<Long> kakaoUserId() {
        if (kakaoId == null || kakaoId.isEmpty() || !kakaoId.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(kakaoId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public boolean isWithdrawing() {
        return this.withdrawalStatus != null;
    }

    public boolean isSignupCompleted() {
        return this.role != null;
    }

    public boolean isOrganization() {
        return this.role == UserRole.ORGANIZATION;
    }

    public boolean isParent() {
        return this.role == UserRole.PARENT;
    }
}
