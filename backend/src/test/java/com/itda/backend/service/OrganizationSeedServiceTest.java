package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

    /**
     * 배포가 겹쳐 두 인스턴스가 동시에 시드를 넣으면 유니크 제약에 걸린다. 그 예외가 그대로
     * 올라가면 ApplicationRunner 가 실패해 새 인스턴스가 아예 뜨지 못한다.
     * 이미 있는 이름을 다시 넣어도 예외 없이 지나가야 한다.
     */
    @Test
    void seedingAnExistingNameDoesNotBlowUp() {
        Organization existing = organizationRepository.findFirstByOrderByIdAsc().orElseThrow();

        assertThatCode(() -> organizationRepository.save(
                Organization.of(existing.getName() + "-다른이름", existing.getType())))
                .doesNotThrowAnyException();
        assertThatCode(() -> organizationSeedService.seedDemoOrganizations())
                .doesNotThrowAnyException();
    }

    /**
     * 경합을 실제로 재현한다. "있나" 검사는 통과했는데 저장 직전에 다른 인스턴스가 먼저 넣어
     * 유니크 제약에 걸리는 상황이다. 이 예외가 밖으로 나가면 서버가 기동하지 못한다.
     */
    @Test
    void concurrentInsertLosingTheRaceDoesNotAbortStartup() {
        OrganizationRepository racing = org.mockito.Mockito.mock(OrganizationRepository.class);
        given(racing.existsByName(org.mockito.ArgumentMatchers.anyString())).willReturn(false);
        given(racing.save(org.mockito.ArgumentMatchers.any(Organization.class)))
                .willThrow(new DataIntegrityViolationException("uk_organization_name"));

        assertThatCode(() -> new OrganizationSeedService(racing).seedDemoOrganizations())
                .doesNotThrowAnyException();
    }

    @Test
    void demoOrganizationsCoverEveryType() {
        assertThat(organizationRepository.findAll())
                .extracting(Organization::getType)
                .contains(
                        com.itda.backend.domain.OrganizationType.CENTER,
                        com.itda.backend.domain.OrganizationType.SCHOOL,
                        com.itda.backend.domain.OrganizationType.ACTIVITY_SUPPORT);
    }
}
