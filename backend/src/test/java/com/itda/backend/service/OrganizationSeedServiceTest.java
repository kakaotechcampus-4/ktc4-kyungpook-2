package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.Organization;
import com.itda.backend.repository.OrganizationRepository;

/**
 * 시드는 기동할 때마다 실행된다. 배포는 ddl-auto: update 라 데이터가 남으므로,
 * 두 번 실행해도 기관이 늘어나지 않아야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrganizationSeedServiceTest {

    @Autowired
    private OrganizationSeedService organizationSeedService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void seedRunsOnStartupSoDemoOrganizationsExist() {
        assertThat(organizationRepository.count()).isPositive();
        assertThat(organizationRepository.findFirstByOrderByIdAsc()).isPresent();
    }

    @Test
    void seedingAgainDoesNotDuplicateRows() {
        long before = organizationRepository.count();

        organizationSeedService.seedDemoOrganizations();

        assertThat(organizationRepository.count()).isEqualTo(before);
    }

    @Test
    void demoOrganizationsCoverEveryType() {
        assertThat(organizationRepository.findAll())
                .extracting(Organization::getType)
                .containsExactlyInAnyOrder(
                        com.itda.backend.domain.OrganizationType.CENTER,
                        com.itda.backend.domain.OrganizationType.SCHOOL,
                        com.itda.backend.domain.OrganizationType.ACTIVITY_SUPPORT);
    }
}
