package com.itda.backend.domain;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

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
 * <p>기관 담당자가 회원가입할 때 기관명·유형·사업자등록번호를 입력하면 그 자리에서 만들어진다.
 * 기관 1곳당 계정 1개이고, 기관을 구분하는 키는 사업자등록번호다.
 *
 * <p>{@code name} 에는 유니크를 걸지 않는다 — 같은 이름의 센터가 실제로 여러 곳 있을 수 있다.
 * {@code business_number} 의 유니크 제약은 지금 넣지 않으면 영영 못 넣는다 — 배포는
 * {@code ddl-auto: update} 라 이미 만들어진 테이블에 제약을 추가해주지 않는다.
 */
@Entity
@Table(
        name = "organization",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_organization_business_number", columnNames = "business_number"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {

    private static final Pattern BUSINESS_NUMBER = Pattern.compile("^\\d{10}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrganizationType type;

    /**
     * 사업자등록번호. 숫자 10자리 형식만 확인하고 진위·체크섬은 검증하지 않는다(MVP 의도적 선택 —
     * 테스트 데이터를 쉽게 넣기 위해서다). 하이픈 없이 저장한다.
     */
    @Column(name = "business_number", nullable = false, length = 10)
    private String businessNumber;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Organization(String name, OrganizationType type, String businessNumber) {
        this.name = name;
        this.type = type;
        this.businessNumber = businessNumber;
    }

    public static Organization of(String name, OrganizationType type, String businessNumber) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("기관명은 필수입니다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("기관 유형은 필수입니다.");
        }
        if (businessNumber == null || !BUSINESS_NUMBER.matcher(businessNumber).matches()) {
            throw new IllegalArgumentException("사업자등록번호는 숫자 10자리여야 합니다.");
        }
        return new Organization(name, type, businessNumber);
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
