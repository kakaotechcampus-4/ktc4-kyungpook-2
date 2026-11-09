package com.itda.backend.domain.user;

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
import com.itda.backend.global.jpa.CreatedAtEntity;

@Entity
@Table(
        name = "terms_agreement",
        uniqueConstraints = @UniqueConstraint(
                name = TermsAgreement.UK_TERMS_AGREEMENT,
                columnNames = {"user_id", "term_key", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsAgreement extends CreatedAtEntity {

    public static final String UK_TERMS_AGREEMENT = "uk_terms_agreement_user_key_version";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "term_key", nullable = false, length = 20)
    private TermKey termKey;

    @Column(nullable = false, length = 20)
    private String version;

    private TermsAgreement(Long userId, TermKey termKey, String version) {
        this.userId = userId;
        this.termKey = termKey;
        this.version = version;
    }

    public static TermsAgreement agree(Long userId, TermKey termKey, String version) {
        if (userId == null || termKey == null || version == null || version.isBlank()) {
            throw new IllegalArgumentException("약관 동의의 회원·약관·버전은 필수입니다.");
        }
        return new TermsAgreement(userId, termKey, version);
    }
}
