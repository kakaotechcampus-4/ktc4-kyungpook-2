package com.itda.backend.service.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.consent.SharingConsent;

@DataJpaTest
@ActiveProfiles("test")
@Import(ConsentStateReader.class)
class ConsentStateReaderTest {

    private static final Long CHILD = 1L;
    private static final Long OTHER_CHILD = 2L;
    private static final Long SCHOOL = 10L;
    private static final Long CENTER = 20L;
    private static final Long MOM = 100L;
    private static final Long DAD = 200L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 10, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ConsentStateReader reader;

    @Test
    void 동의_행이_없으면_아직_안_함() {
        assertThat(reader.stateOf(CHILD, SCHOOL)).isEqualTo(ConsentState.NOT_GRANTED);
    }

    @Test
    void 살아_있는_동의가_있으면_켜짐() {
        grant(CHILD, MOM, SCHOOL);

        assertThat(reader.stateOf(CHILD, SCHOOL)).isEqualTo(ConsentState.GRANTED);
    }

    @Test
    void 동의_행이_모두_철회됐으면_꺼짐() {
        revoke(grant(CHILD, MOM, SCHOOL), ConsentRevokeReason.GUARDIAN_REVOKED);

        assertThat(reader.stateOf(CHILD, SCHOOL)).isEqualTo(ConsentState.REVOKED);
    }

    @Test
    void 철회_뒤_다른_보호자가_다시_동의하면_켜짐() {
        revoke(grant(CHILD, DAD, SCHOOL), ConsentRevokeReason.GUARDIAN_REVOKED);
        grant(CHILD, MOM, SCHOOL);

        assertThat(reader.stateOf(CHILD, SCHOOL)).isEqualTo(ConsentState.GRANTED);
        assertThat(reader.statesOf(List.of(CHILD)).get(CHILD)).containsEntry(SCHOOL, ConsentState.GRANTED);
    }

    @Test
    void 한_보호자_동의만_꺼져도_남은_동의가_있으면_켜짐() {
        grant(CHILD, MOM, SCHOOL);
        revoke(grant(CHILD, DAD, SCHOOL), ConsentRevokeReason.GUARDIAN_UNLINKED);

        assertThat(reader.stateOf(CHILD, SCHOOL)).isEqualTo(ConsentState.GRANTED);
        assertThat(reader.statesOf(List.of(CHILD)).get(CHILD)).containsEntry(SCHOOL, ConsentState.GRANTED);
    }

    @Test
    void 여러_아이의_기관별_상태를_한_번에_계산한다() {
        grant(CHILD, MOM, SCHOOL);
        revoke(grant(CHILD, MOM, CENTER), ConsentRevokeReason.GUARDIAN_REVOKED);
        grant(OTHER_CHILD, DAD, CENTER);

        Map<Long, Map<Long, ConsentState>> states = reader.statesOf(List.of(CHILD, OTHER_CHILD));

        assertThat(states.get(CHILD))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        SCHOOL, ConsentState.GRANTED,
                        CENTER, ConsentState.REVOKED));
        assertThat(states.get(OTHER_CHILD))
                .containsExactlyInAnyOrderEntriesOf(Map.of(CENTER, ConsentState.GRANTED));
    }

    @Test
    void 묻지_않은_아이의_행은_섞이지_않는다() {
        grant(CHILD, MOM, SCHOOL);
        grant(OTHER_CHILD, DAD, CENTER);

        assertThat(reader.statesOf(List.of(CHILD))).containsOnlyKeys(CHILD);
    }

    @Test
    void 아이_목록이_비면_빈_결과() {
        grant(CHILD, MOM, SCHOOL);

        assertThat(reader.statesOf(List.of())).isEmpty();
        assertThat(reader.grantedChildIds(List.of(), SCHOOL)).isEmpty();
    }

    @Test
    void 그_기관_동의가_켜진_아이만_고른다() {
        Long revoked = 3L;
        Long none = 4L;
        grant(CHILD, MOM, SCHOOL);
        revoke(grant(revoked, MOM, SCHOOL), ConsentRevokeReason.GUARDIAN_REVOKED);
        grant(OTHER_CHILD, DAD, CENTER);

        assertThat(reader.grantedChildIds(List.of(CHILD, OTHER_CHILD, revoked, none), SCHOOL))
                .containsExactly(CHILD);
    }

    @Test
    void 인사이트_동의는_주간_인사이트를_허락했을_때만_켜진다() {
        grant(CHILD, MOM, SCHOOL);
        em.persistAndFlush(SharingConsent.grant(CHILD, MOM, CENTER,
                List.of(ConsentField.DAILY_SUMMARY, ConsentField.WEEKLY_INSIGHT)));

        assertThat(reader.insightStateOf(CHILD, SCHOOL)).isEqualTo(ConsentState.NOT_GRANTED);
        assertThat(reader.insightStateOf(CHILD, CENTER)).isEqualTo(ConsentState.GRANTED);
        assertThat(reader.insightStatesOf(List.of(CHILD)).get(CHILD))
                .containsEntry(SCHOOL, ConsentState.NOT_GRANTED)
                .containsEntry(CENTER, ConsentState.GRANTED);
    }

    @Test
    void 꺼진_인사이트_동의보다_살아_있는_하루_요약_동의가_세다() {
        SharingConsent dadWeekly = em.persistAndFlush(
                SharingConsent.grant(CHILD, DAD, CENTER, List.of(ConsentField.WEEKLY_INSIGHT)));
        revoke(dadWeekly, ConsentRevokeReason.GUARDIAN_REVOKED);
        assertThat(reader.insightStateOf(CHILD, CENTER)).isEqualTo(ConsentState.REVOKED);

        grant(CHILD, MOM, CENTER);
        assertThat(reader.insightStateOf(CHILD, CENTER)).isEqualTo(ConsentState.NOT_GRANTED);
    }

    private SharingConsent grant(Long childId, Long guardianId, Long organizationId) {
        return em.persistAndFlush(
                SharingConsent.grant(childId, guardianId, organizationId, List.of(ConsentField.DAILY_SUMMARY)));
    }

    private void revoke(SharingConsent consent, ConsentRevokeReason reason) {
        consent.revoke(consent.getGuardianId(), reason, NOW);
        em.flush();
    }
}
