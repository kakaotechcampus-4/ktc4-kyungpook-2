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
    @Column(name = "kakao_id", nullable = false, length = 64)
    private String kakaoId;

    /** 카카오 닉네임. 사용자가 동의를 거부할 수 있어 nullable 이다. */
    @Column(length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    /** 보호자는 소속 기관이 없어 null 이다. FK 제약 없음. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private User(String kakaoId, String name, UserRole role, Long organizationId) {
        this.kakaoId = kakaoId;
        this.name = name;
        this.role = role;
        this.organizationId = organizationId;
    }

    public static User of(String kakaoId, String name, UserRole role, Long organizationId) {
        if (role == UserRole.PARENT && organizationId != null) {
            throw new IllegalArgumentException("보호자는 소속 기관을 가질 수 없습니다.");
        }
        return new User(kakaoId, name, role, organizationId);
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
