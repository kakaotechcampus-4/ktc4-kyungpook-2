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

/**
 * "이 회원이 이 약관의 이 버전에 언제 동의했다" 는 기록 한 줄 (DB 스키마 §4.3).
 *
 * <p><b>쌓기만 한다.</b> 약관이 바뀌어 다시 동의하면 새 버전 줄이 하나 더 생기고 옛 줄은 그대로 남는다.
 * 수정·삭제 메서드도 {@code deleted_at} 도 두지 않는다 — 누가 언제 어느 판에 동의했는지가 이 테이블의 전부다.
 * 필수 약관을 철회하는 길은 회원 탈퇴다.
 *
 * <p>회원과 연관관계를 맺지 않는다. 다른 테이블처럼 FK 제약 없이 {@code userId} 컬럼만 둔다.
 */
@Entity
@Table(
        name = "terms_agreement",
        uniqueConstraints = @UniqueConstraint(
                name = TermsAgreement.UK_TERMS_AGREEMENT,
                columnNames = {"user_id", "term_key", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsAgreement extends CreatedAtEntity {

    /** 같은 판에 두 번 동의해도 줄은 하나다. */
    public static final String UK_TERMS_AGREEMENT = "uk_terms_agreement_user_key_version";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "term_key", nullable = false, length = 20)
    private TermKey termKey;

    /** 동의한 약관의 판. 서버 상수(TermsCatalog)가 정한다 — 프론트가 보내지 않는다. */
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
