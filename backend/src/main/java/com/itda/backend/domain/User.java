package com.itda.backend.domain;

import java.time.LocalDateTime;

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

    /** 카카오 닉네임. 사용자가 동의를 거부할 수 있어 nullable 이다. */
    @Column(length = 100)
    private String name;

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
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private User(String kakaoId, String name) {
        this.kakaoId = kakaoId;
        this.name = name;
    }

    /** 카카오 로그인 직후의 회원. 역할과 소속은 아직 없다. */
    public static User pending(String kakaoId, String name) {
        return new User(kakaoId, name);
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
     * 카카오 닉네임을 최신값으로 맞춘다.
     *
     * <p>비어 있는 값으로는 덮어쓰지 않는다 — 처음엔 동의했다가 나중에 동의를 철회하고
     * 다시 로그인하면 응답에 닉네임이 빠지는데, 그때 이미 저장해 둔 이름까지 지우면 안 된다.
     */
    public void updateName(String name) {
        if (name == null || name.isBlank() || name.equals(this.name)) {
            return;
        }
        this.name = name;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    /** 탈퇴한 사람이 같은 카카오 계정으로 다시 들어오면 새 행을 만들지 않고 되살린다 (§3.3). */
    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public boolean isSignupCompleted() {
        return this.role != null;
    }

    public boolean isOrganization() {
        return this.role == UserRole.ORGANIZATION;
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
