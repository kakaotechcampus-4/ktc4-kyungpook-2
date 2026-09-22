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
 * 아이를 돌보는 기관. 학교·센터·활동지원기관을 한 테이블로 다룬다.
 *
 * <p>기관 생성 API 는 만들지 않기로 했다 — 시연에 쓸 행은 {@code OrganizationSeeder} 가
 * 기동할 때 미리 넣는다. 그래서 이 엔티티를 쓰기로 만드는 주체는 시더뿐이다.
 *
 * <p>{@code name} 에 유니크를 건 이유: 쓰는 쪽이 시더 하나뿐이라 중복 이름이 생길 일이 없고,
 * 이 제약이 있어야 시더가 "행 단위로" 멱등해진다(이미 있는 이름은 건너뛴다).
 * 제약은 지금 넣지 않으면 영영 못 넣는다 — 배포는 {@code ddl-auto: update} 라
 * 이미 만들어진 테이블에 제약을 추가해주지 않는다.
 */
@Entity
@Table(
        name = "organization",
        uniqueConstraints = @UniqueConstraint(name = "uk_organization_name", columnNames = "name"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrganizationType type;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Organization(String name, OrganizationType type) {
        this.name = name;
        this.type = type;
    }

    public static Organization of(String name, OrganizationType type) {
        return new Organization(name, type);
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
