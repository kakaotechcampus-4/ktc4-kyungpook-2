package com.itda.backend.fixture;

import java.util.concurrent.atomic.AtomicLong;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;

public final class OrganizationFixture {

    private static final AtomicLong NEXT_BUSINESS_NUMBER = new AtomicLong(9_000_000_000L);

    private OrganizationFixture() {
    }

    public static Organization center() {
        return Organization.of("햇살아동발달센터", OrganizationType.CENTER, nextBusinessNumber());
    }

    public static String nextBusinessNumber() {
        return String.valueOf(NEXT_BUSINESS_NUMBER.getAndIncrement());
    }
}
