package com.itda.backend.domain.organization;

/** 인계 코드 상태. 만료는 상태로 두지 않고 {@code expiresAt} 으로만 판단한다. */
public enum OrganizationHandoverStatus {
    PENDING,
    ACCEPTED,
    CANCELLED
}
