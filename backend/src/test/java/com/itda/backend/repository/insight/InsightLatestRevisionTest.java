package com.itda.backend.repository.insight;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class InsightLatestRevisionTest extends InsightLatestRevisionContract {
}
