package com.itda.backend.domain.consent;

/** 동의 행의 상태. 철회가 곧 {@code REVOKED} 라 동의 행은 지우지 않는다 (DB 스키마 §5.5). */
public enum SharingConsentStatus {
    ACTIVE,
    REVOKED
}
