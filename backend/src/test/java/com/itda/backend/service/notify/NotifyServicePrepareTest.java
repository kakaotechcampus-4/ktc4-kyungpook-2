package com.itda.backend.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.dto.request.notify.NotifyAgentRequest;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.insight.InsightEvidenceReader;

@DataJpaTest
@ActiveProfiles("test")
class NotifyServicePrepareTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 5);
    private static final LocalDate TO = LocalDate.of(2026, 10, 11);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InsightResultRepository insightResultRepository;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private final InsightEvidenceReader evidenceReader = mock(InsightEvidenceReader.class);
    private NotifyService notifyService;
    private Organization school;
    private Organization center;
    private Child junho;

    @BeforeEach
    void setUp() {
        given(evidenceReader.read(any())).willReturn(Map.of());
        notifyService = new NotifyService(insightResultRepository, childRepository, childOrganizationRepository,
                organizationRepository, evidenceReader);
        school = em.persist(Organization.of("햇살초등학교", OrganizationType.SCHOOL, "1234567890"));
        center = em.persist(Organization.of("푸른센터", OrganizationType.CENTER, "0987654321"));
        junho = em.persist(Child.of("김준호", LocalDate.of(2019, 3, 1)));
        Child seojun = em.persist(Child.of("서준호", LocalDate.of(2019, 5, 1)));
        em.persist(ChildOrganization.of(junho.getId(), school.getId()));
        em.persist(ChildOrganization.of(junho.getId(), center.getId()));
        em.persist(ChildOrganization.of(seojun.getId(), center.getId()));
    }

    private void approvedInsight(String sourceInstitutionIds) {
        InsightResult insight = InsightResult.of(junho.getId(), FROM, TO, 1, "상황", "반응", "본문", "[]", "[]", "[]",
                sourceInstitutionIds, true, "{}");
        insight.approveGate2();
        em.persistAndFlush(insight);
    }

    @Test
    void 기관_번호가_숫자_문자열로_저장돼_있어도_그_기관을_싣는다() {
        approvedInsight("[" + school.getId() + ", \"" + center.getId() + "\"]");

        NotifyAgentRequest request = notifyService.prepare(junho.getId(), FROM, TO).orElseThrow().request();

        assertThat(request.insights().get(0).institutionTypes()).containsExactlyInAnyOrder("학교", "센터");
        assertThat(request.otherChildNames()).containsExactly("서준호");
    }
}
