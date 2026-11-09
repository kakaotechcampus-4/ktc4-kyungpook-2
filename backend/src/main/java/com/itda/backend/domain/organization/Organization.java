package com.itda.backend.domain.organization;

import java.util.regex.Pattern;

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
        name = "organization",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = Organization.UK_BUSINESS_NUMBER, columnNames = "business_number"),
                @UniqueConstraint(name = Organization.UK_CODE, columnNames = "code")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization extends TimestampedEntity {

    public static final String UK_BUSINESS_NUMBER = "uk_organization_business_number";
    public static final String UK_CODE = "uk_organization_code";
    public static final int CODE_LENGTH = 8;

    private static final Pattern BUSINESS_NUMBER = Pattern.compile("^\\d{10}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrganizationType type;

    @Column(name = "business_number", nullable = false, length = 10)
    private String businessNumber;

    @Column(length = CODE_LENGTH)
    private String code;

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

    public void assignCode(String code) {
        if (this.code != null) {
            throw new IllegalStateException("기관 코드는 이미 발급됐습니다.");
        }
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("기관 코드는 8자리여야 합니다.");
        }
        this.code = code;
    }
}
