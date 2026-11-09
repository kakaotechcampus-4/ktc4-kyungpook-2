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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 회원. 카카오는 신원 확인 수단일 뿐이고, 역할·소속의 기준은 이 테이블이다.
 *
 * <p>카카오 로그인으로 역할 없는(가입 미완료) 행이 먼저 생기고, 가입 API 가 역할을 정한다.
 *
 * <p><b>테이블 이름이 {@code users} 인 이유.</b> {@code USER} 는 PostgreSQL 예약어이고
 * H2 의 {@code MODE=PostgreSQL} 에서도 마찬가지다. {@code @Table(name = "user")} 로 두면
 * 테이블 생성 자체가 실패한다.
 *
 * <p><b>기관과 연관관계를 맺지 않는다.</b> 팀 합의대로 FK 제약과 {@code @ManyToOne} 없이
 * {@code organizationId} 컬럼만 둔다. 이 값이 실제 기관을 가리키는지는 Service 가 검증한다.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_kakao_id", columnNames = "kakao_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    /** 탈퇴를 마친 행의 {@code kakao_id}. 원래 값과 관계없어 되찾을 수 없다. 뒤에 내부 id 를 붙여 유니크를 지킨다. */
    public static final String WITHDRAWN_KAKAO_ID_PREFIX = "withdrawn:";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 카카오 회원번호. 숫자지만 문자열로 보관한다 — 우리가 계산에 쓰지 않는 식별자이고,
     * 기존 코드가 이미 {@code String.valueOf(kakaoId)} 로 다루고 있다.
     *
     * <p>유니크 제약은 여기가 마지막 기회다. 배포는 {@code ddl-auto: update} 라
     * 나중에 붙여주지 않는다.
     */
    @Column(name = "kakao_id", nullable = false, length = 50)
    private String kakaoId;

    /**
     * null 이면 가입 미완료다. 카카오 로그인은 신원 확인만 하고, 역할은 가입 API 에서
     * {@link #completeSignup} 으로 정해진다. 한 번 정해지면 바뀌지 않는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserRole role;

    /** 기관 담당자만 가진다. 보호자와 가입 미완료 회원은 null 이다. FK 제약 없음. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 탈퇴 표시. null 이면 활성 계정이다 (DB 스키마 §3.2 soft delete).
     *
     * <p>행을 지우지 않는 이유: FK 제약이 없어서, 지우면 이 사용자를 가리키는 child_guardian ·
     * sharing_consent · human_review.reviewer_id 가 존재하지 않는 id 를 가리킨 채 남는다.
     * 대신 탈퇴를 마칠 때 카카오 회원번호를 지운다({@link #completeWithdrawal}). 이름 같은 개인정보는 처음부터 저장하지 않는다.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 탈퇴 진행 단계. null 이면 진행 중인 탈퇴가 없다 (DB 스키마 §4.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "withdrawal_status", length = 20)
    private WithdrawalStatus withdrawalStatus;

    @Column(name = "withdrawal_requested_at")
    private LocalDateTime withdrawalRequestedAt;

    /**
     * 카카오 연결 해제를 부르기 직전에 남긴 시각. 값이 있으면 이전 호출이 카카오에서 성공했을 수도 있다 —
     * 그 뒤의 분명한 오류는 "이번 호출이 실패했다" 는 뜻일 뿐이라 탈퇴 접수를 취소하지 않는다.
     */
    @Column(name = "kakao_unlink_attempted_at")
    private LocalDateTime kakaoUnlinkAttemptedAt;

    private User(String kakaoId) {
        this.kakaoId = kakaoId;
    }

    /**
     * 카카오 로그인 직후의 회원. 역할과 소속은 아직 없다.
     *
     * <p>카카오 닉네임 같은 개인정보는 받지 않는다. 화면은 사람 이름 대신 "아이 이름 + 보호자" 나 기관명을 쓴다.
     */
    public static User pending(String kakaoId) {
        return new User(kakaoId);
    }

    /**
     * 가입을 마친다 — 역할과 소속을 확정한다.
     *
     * <p>이미 가입한 회원이면 막는다. 서비스가 먼저 409 로 거르지만, 역할이 바뀌면 그 사람이
     * 볼 수 있는 데이터 범위가 통째로 바뀌므로 엔티티도 스스로 지킨다.
     * 소속 없는 기관 담당자도 만들지 않는다 — /auth/me 가 institutionId 없는 org 응답을 내보내게 된다.
     */
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

    /**
     * 탈퇴를 접수한다. 기관 담당자는 탈퇴할 수 없다 — 기관에 남은 명부 · 일지를 어떻게 할지 정하지 않았다.
     * 다시 신청하는 것이면 새로운 첫 호출이므로 이전 호출 시각을 지운다.
     */
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
        this.withdrawalStatus = WithdrawalStatus.REQUESTED;
        this.withdrawalRequestedAt = now;
        this.kakaoUnlinkAttemptedAt = null;
    }

    /**
     * 카카오 연결 해제를 부르기 직전에 남긴다.
     *
     * @return 이번이 이 접수의 첫 호출인지
     */
    public boolean markKakaoUnlinkAttempt(LocalDateTime now) {
        requireStatus(WithdrawalStatus.REQUESTED);
        boolean first = this.kakaoUnlinkAttemptedAt == null;
        this.kakaoUnlinkAttemptedAt = now;
        return first;
    }

    /** 카카오 연결이 끊긴 것이 확정됐다. 이 뒤로는 DB 정리만 남는다. */
    public void markKakaoUnlinked() {
        requireStatus(WithdrawalStatus.REQUESTED);
        this.withdrawalStatus = WithdrawalStatus.KAKAO_UNLINKED;
    }

    /** 첫 호출이 분명히 실패해 탈퇴 접수를 취소한다. 회원은 그대로 서비스를 쓴다. */
    public void cancelWithdrawal() {
        requireStatus(WithdrawalStatus.REQUESTED);
        clearWithdrawal();
    }

    /**
     * 탈퇴를 마친다. 행은 남기고 카카오 회원번호를 지운다 (카카오 정책: 회원번호도 파기 대상이다).
     * 같은 카카오 계정으로 다시 오면 이 행을 찾지 못하므로 새 회원으로 가입한다.
     */
    public void completeWithdrawal(LocalDateTime now) {
        requireStatus(WithdrawalStatus.KAKAO_UNLINKED);
        eraseIdentity();
        this.deletedAt = now;
        clearWithdrawal();
    }

    /**
     * 옛 방식(카카오 회원번호를 남긴 채 탈퇴 표시만 함)으로 탈퇴한 행의 식별정보를 지운다. 그 사람이 다시 로그인하면
     * 이 행을 비우고 새 회원을 만든다 — 되살리지 않는다.
     */
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
    }

    private void requireStatus(WithdrawalStatus expected) {
        if (this.withdrawalStatus != expected) {
            throw new IllegalStateException("탈퇴 단계가 " + expected + " 가 아닙니다: " + this.withdrawalStatus);
        }
    }

    /**
     * 카카오에 연결 해제를 요청할 회원번호. 개발용 로그인 회원({@code dev:})처럼 카카오 계정이 아닌 회원은 비어 있다.
     */
    public Optional<Long> kakaoUserId() {
        if (kakaoId == null || kakaoId.isEmpty() || !kakaoId.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(kakaoId));
        } catch (NumberFormatException e) {
            // 숫자만 있어도 long 범위를 넘으면 카카오 회원번호가 아니다
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

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
