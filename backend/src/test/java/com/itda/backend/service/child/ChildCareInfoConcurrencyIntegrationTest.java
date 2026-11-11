package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildCareInfo;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.request.child.UpdateCareInfoRequest;
import com.itda.backend.dto.request.child.UpdateCareInfoRequest.MedicationRequest;
import com.itda.backend.fixture.LockContention;
import com.itda.backend.fixture.LockContention.Held;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.child.ChildCareInfoRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.invitation.ChildGuardianService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ChildCareInfoConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_child_care_info_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private DataSource dataSource;
    @Autowired private ChildCareInfoService childCareInfoService;
    @Autowired private ChildGuardianService childGuardianService;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private ChildGuardianRepository childGuardianRepository;
    @Autowired private ChildCareInfoRepository childCareInfoRepository;

    private LockContention contention;
    private User mom;
    private User dad;
    private Long childId;

    @BeforeEach
    void setUp() {
        contention = new LockContention(transactionTemplate, entityManager, dataSource);
        mom = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-mom")));
        dad = TermsFixture.agreeAll(termsAgreementRepository, userRepository.save(UserFixture.parent("kakao-dad")));
        childId = childRepository.save(Child.of("임유진", LocalDate.of(2017, 3, 14))).getId();
        childGuardianRepository.save(ChildGuardian.link(childId, mom.getId(), 1L));
        childGuardianRepository.save(ChildGuardian.link(childId, dad.getId(), 1L));
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM child_care_info");
            statement.executeUpdate("DELETE FROM child_organization_request");
            statement.executeUpdate("DELETE FROM sharing_consent");
            statement.executeUpdate("DELETE FROM child_guardian");
            statement.executeUpdate("DELETE FROM child");
            statement.executeUpdate("DELETE FROM terms_agreement");
            statement.executeUpdate("DELETE FROM users");
        }
    }

    @Test
    void 저장끼리는_줄을_서고_뒤에_온_쪽이_덮어쓰며_행은_하나다() throws Exception {
        Held<Object> held = contention.firstHoldsThen(
                () -> childCareInfoService.save(principal(mom), childId, request("땅콩")),
                () -> mockMvc.perform(withCsrf(put(carePath()), auth(dad))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("계란")))
                        .andReturn());

        assertThat(held.second().getResponse().getStatus()).isEqualTo(200);
        assertThat(childCareInfoRepository.count()).isEqualTo(1);
        ChildCareInfo stored = childCareInfoRepository.findByChildId(childId).orElseThrow();
        assertThat(stored.getAllergies()).containsExactly("계란");
        assertThat(stored.getUpdatedBy()).isEqualTo(dad.getId());
    }

    @Test
    void 연결_해제가_먼저면_저장은_403이고_돌봄_정보가_생기지_않는다() throws Exception {
        Held<Object> held = contention.firstHoldsThen(
                () -> {
                    childGuardianService.unlinkBySelf(principal(dad), childId);
                    return null;
                },
                () -> mockMvc.perform(withCsrf(put(carePath()), auth(dad))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("계란")))
                        .andReturn());

        assertForbidden(held.second());
        assertThat(childCareInfoRepository.count()).isZero();
    }

    @Test
    void 연결_해제가_먼저면_저장은_403이고_저장된_값이_바뀌지_않는다() throws Exception {
        childCareInfoService.save(principal(mom), childId, request("땅콩"));

        Held<Object> held = contention.firstHoldsThen(
                () -> {
                    childGuardianService.unlinkBySelf(principal(dad), childId);
                    return null;
                },
                () -> mockMvc.perform(withCsrf(put(carePath()), auth(dad))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("계란")))
                        .andReturn());

        assertForbidden(held.second());
        ChildCareInfo stored = childCareInfoRepository.findByChildId(childId).orElseThrow();
        assertThat(stored.getAllergies()).containsExactly("땅콩");
        assertThat(stored.getUpdatedBy()).isEqualTo(mom.getId());
    }

    private static UpdateCareInfoRequest request(String allergy) {
        return new UpdateCareInfoRequest(List.of(allergy), List.of(new MedicationRequest("○○정", "점심 식후")));
    }

    private static String body(String allergy) {
        return """
                {"allergies": ["%s"], "medications": [{"name": "○○정", "time": "점심 식후"}]}
                """.formatted(allergy);
    }

    private static void assertForbidden(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
    }

    private String carePath() {
        return "/api/v1/children/" + childId + "/care-info";
    }

    private static String principal(User user) {
        return String.valueOf(user.getId());
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(principal(user)));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, Cookie... cookies)
            throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie[] all = new Cookie[cookies.length + 1];
        System.arraycopy(cookies, 0, all, 0, cookies.length);
        all[cookies.length] = csrf;
        return request.cookie(all).header("X-XSRF-TOKEN", csrf.getValue());
    }
}
