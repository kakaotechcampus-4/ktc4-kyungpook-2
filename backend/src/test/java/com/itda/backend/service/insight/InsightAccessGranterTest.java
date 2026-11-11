package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.repository.sharing.SharingHistoryRepository;

@DataJpaTest
@ActiveProfiles("test")
@Import(InsightAccessGranter.class)
class InsightAccessGranterTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 30, 9, 0);
    private static final Long CHILD = 1L;
    private static final Long OTHER_CHILD = 2L;
    private static final Long MOTHER = 7L;
    private static final Long FATHER = 8L;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InsightAccessGranter granter;

    @Autowired
    private SharingHistoryRepository sharingHistoryRepository;

    private Long schoolId;
    private Long centerId;

    @BeforeEach
    void setUp() {
        schoolId = em.persistAndFlush(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000001")).getId();
        centerId = em.persistAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1000000002")).getId();
    }

    @Test
    void 새로_연결되면_승인된_글이_모두_열린다() {
        Long first = approved(CHILD, 1, schoolId);
        Long second = approved(CHILD, 2, schoolId, centerId);
        grant(CHILD, first, schoolId, ReceiverType.GUARDIAN, MOTHER);
        grant(CHILD, second, schoolId, ReceiverType.GUARDIAN, MOTHER);

        granter.grantForLinkedGuardian(CHILD, FATHER, AT);
        em.flush();
        em.clear();

        assertThat(activeOf(FATHER)).extracting(SharingHistory::getInsightResultId).containsExactly(first, second);
        assertThat(activeOf(FATHER)).extracting(SharingHistory::getSenderOrganizationId)
                .containsOnly(schoolId);
        assertThat(activeOf(FATHER)).extracting(SharingHistory::getReadAt).containsOnlyNulls();
    }

    @Test
    void 검토_전이나_보류된_글은_열지_않는다() {
        insight(CHILD, 1, schoolId);
        Long held = insight(CHILD, 2, schoolId);
        em.find(InsightResult.class, held).holdGate2();
        em.flush();

        granter.grantForLinkedGuardian(CHILD, FATHER, AT);
        em.flush();

        assertThat(sharingHistoryRepository.findAll()).isEmpty();
    }

    @Test
    void 다른_아이의_글은_열지_않는다() {
        approved(OTHER_CHILD, 1, schoolId);

        granter.grantForLinkedGuardian(CHILD, FATHER, AT);
        em.flush();

        assertThat(sharingHistoryRepository.findAll()).isEmpty();
    }

    @Test
    void 다시_연결하면_거뒀던_권한을_되살리고_본_시각은_남긴다() {
        Long before = approved(CHILD, 1, schoolId);
        Long old = grant(CHILD, before, schoolId, ReceiverType.GUARDIAN, FATHER);
        LocalDateTime readAt = LocalDateTime.of(2026, 10, 20, 8, 0);
        sharingHistoryRepository.markReadIfUnread(old, readAt);
        em.find(SharingHistory.class, old).revoke(ConsentRevokeReason.GUARDIAN_UNLINKED, AT.minusDays(3));
        em.flush();
        Long whileUnlinked = approved(CHILD, 2, schoolId);
        grant(CHILD, whileUnlinked, schoolId, ReceiverType.GUARDIAN, MOTHER);

        granter.grantForLinkedGuardian(CHILD, FATHER, AT);
        em.flush();
        em.clear();

        SharingHistory revived = em.find(SharingHistory.class, old);
        assertThat(revived.isActive()).isTrue();
        assertThat(revived.getRevokeReason()).isNull();
        assertThat(revived.getGrantedAt()).isEqualTo(AT);
        assertThat(revived.getReadAt()).isEqualTo(readAt);
        assertThat(activeOf(FATHER)).extracting(SharingHistory::getInsightResultId)
                .containsExactly(before, whileUnlinked);
        assertThat(sharingHistoryRepository.findAll()).hasSize(3);
    }

    @Test
    void 다른_권한_행이_없으면_승인_주체_규칙으로_보낸_기관을_정한다() {
        Long alone = approved(CHILD, 1, centerId, schoolId);

        granter.grantForLinkedGuardian(CHILD, FATHER, AT);
        em.flush();
        em.clear();

        assertThat(activeOf(FATHER)).singleElement().satisfies(grant -> {
            assertThat(grant.getInsightResultId()).isEqualTo(alone);
            assertThat(grant.getSenderOrganizationId()).isEqualTo(schoolId);
        });
    }

    @Test
    void 근거_기관이_모두_사라진_글은_건너뛴다() {
        approved(CHILD, 1, 999L);

        granter.grantForLinkedGuardian(CHILD, FATHER, AT);
        em.flush();

        assertThat(sharingHistoryRepository.findAll()).isEmpty();
    }

    @Test
    void 다른_보호자와_기관의_권한은_건드리지_않는다() {
        Long insight = approved(CHILD, 1, schoolId);
        Long mother = grant(CHILD, insight, schoolId, ReceiverType.GUARDIAN, MOTHER);
        Long center = grant(CHILD, insight, schoolId, ReceiverType.ORGANIZATION, centerId);
        em.find(SharingHistory.class, center).revoke(ConsentRevokeReason.GUARDIAN_REVOKED, AT.minusDays(1));
        em.flush();
        LocalDateTime motherGrantedAt = em.find(SharingHistory.class, mother).getGrantedAt();
        em.clear();

        granter.grantForLinkedGuardian(CHILD, FATHER, AT);
        em.flush();
        em.clear();

        assertThat(em.find(SharingHistory.class, mother).getGrantedAt()).isEqualTo(motherGrantedAt);
        assertThat(em.find(SharingHistory.class, center).isActive()).isFalse();
    }

    private List<SharingHistory> activeOf(Long guardianId) {
        return sharingHistoryRepository.findAll().stream()
                .filter(grant -> grant.getReceiverType() == ReceiverType.GUARDIAN
                        && grant.getReceiverId().equals(guardianId) && grant.isActive())
                .sorted((a, b) -> a.getInsightResultId().compareTo(b.getInsightResultId()))
                .toList();
    }

    private Long approved(Long childId, int revision, Long... sourceInstitutionIds) {
        Long id = insight(childId, revision, sourceInstitutionIds);
        em.find(InsightResult.class, id).approveGate2();
        em.flush();
        em.clear();
        return id;
    }

    private Long insight(Long childId, int revision, Long... sourceInstitutionIds) {
        String json = "[" + String.join(",", Arrays.stream(sourceInstitutionIds).map(String::valueOf).toList()) + "]";
        Long id = em.persistAndFlush(InsightResult.of(childId, FROM, TO, revision, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]", json,
                sourceInstitutionIds.length > 1, null)).getId();
        em.clear();
        return id;
    }

    private Long grant(Long childId, Long insightId, Long senderId, ReceiverType receiverType, Long receiverId) {
        Long id = em.persistAndFlush(SharingHistory.grant(childId, insightId, senderId, receiverType, receiverId))
                .getId();
        em.clear();
        return id;
    }
}
