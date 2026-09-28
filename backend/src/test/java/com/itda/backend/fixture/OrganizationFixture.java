package com.itda.backend.fixture;

import java.util.concurrent.atomic.AtomicLong;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;

/**
 * 테스트용 기관. 기관 시드가 없어졌으므로 기관이 필요한 테스트는 직접 만든다.
 *
 * <p>SpringBootTest 들은 같은 H2 DB 를 공유하고 롤백하지 않는다. 사업자등록번호에 유니크 제약이
 * 있어서, 고정값을 쓰면 테스트 순서에 따라 서로 충돌한다. 그래서 호출마다 다른 번호를 준다.
 */
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
