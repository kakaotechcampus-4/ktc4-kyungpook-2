package com.itda.backend.repository.insight;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/** 마지막 회차 쿼리 계약 — H2 PostgreSQL 호환 모드. PostgreSQL 은 {@link InsightLatestRevisionPostgresIntegrationTest}. */
@DataJpaTest
@ActiveProfiles("test")
class InsightLatestRevisionTest extends InsightLatestRevisionContract {
}
