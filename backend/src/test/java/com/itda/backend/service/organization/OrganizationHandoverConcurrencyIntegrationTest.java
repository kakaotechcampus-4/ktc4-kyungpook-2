package com.itda.backend.service.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationHandoverStatus;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.request.user.SignupRequest;
import com.itda.backend.exception.organization.OrganizationErrorCode;
import com.itda.backend.exception.organization.OrganizationException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.organization.OrganizationHandoverRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.user.KakaoUnlinkClient;
import com.itda.backend.service.user.KakaoUnlinkResult;
import com.itda.backend.service.user.UserService;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class OrganizationHandoverConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_handover_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private UserService userService;
    @Autowired private OrganizationHandoverService handoverService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationHandoverRepository handoverRepository;

    @MockitoBean private KakaoUnlinkClient kakaoUnlinkClient;

    private static String kakaoId() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
    }

    @Test
    void 같은_코드로_동시에_가입하면_한_명만_기관을_받는다() throws Exception {
        given(kakaoUnlinkClient.unlink(anyLong())).willReturn(KakaoUnlinkResult.UNLINKED);
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        User staff = userRepository.save(UserFixture.organizationUser(kakaoId(), organization.getId()));
        String code = handoverService.issue(String.valueOf(staff.getId())).code();
        SignupRequest request = new SignupRequest("org", null, null, null, code);

        List<User> successors = List.of(userRepository.save(User.pending(kakaoId())),
                userRepository.save(User.pending(kakaoId())));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(successors.size());
        List<Future<Boolean>> results = new ArrayList<>();
        for (User successor : successors) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    userService.completeSignup(String.valueOf(successor.getId()), request);
                    return true;
                } catch (OrganizationException e) {
                    assertThat(e.getErrorCode()).isEqualTo(OrganizationErrorCode.INVALID_HANDOVER_CODE);
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(20, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        long attached = successors.stream()
                .map(s -> userRepository.findById(s.getId()).orElseThrow())
                .filter(s -> organization.getId().equals(s.getOrganizationId()))
                .count();
        assertThat(attached).isEqualTo(1);
        assertThat(handoverRepository.findByCode(code).orElseThrow().getStatus())
                .isEqualTo(OrganizationHandoverStatus.ACCEPTED);
    }
}
