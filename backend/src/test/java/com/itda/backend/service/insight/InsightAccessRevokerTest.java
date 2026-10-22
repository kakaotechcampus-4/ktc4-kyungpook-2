package com.itda.backend.service.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.repository.sharing.SharingHistoryRepository;

/** 동의가 꺼지면 이미 내준 열람 권한을 거둔다 (#213). */
@DataJpaTest
@ActiveProfiles("test")
@Import(InsightAccessRevoker.class)
class InsightAccessRevokerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 17);
    private static final LocalDate TO = LocalDate.of(2026, 8, 23);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 22, 9, 0);
    private static final Long CHILD = 1L;
    private static final Long OTHER_CHILD = 2L;
    private static final Long SCHOOL = 10L;
    private static final Long CENTER = 20L;
    private static final Long GUARDIAN = 7L;
    private static final Long OTHER_GUARDIAN = 8L;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InsightAccessRevoker revoker;

    @Autowired
    private SharingHistoryRepository sharingHistoryRepository;

    private Long fromSchoolAndCenter;
    private Long fromSchoolOnly;

    @BeforeEach
    void setUp() {
        fromSchoolAndCenter = insight(CHILD, 1, SCHOOL, CENTER);
        fromSchoolOnly = insight(CHILD, 2, SCHOOL);
    }

    /** 철회의 목적은 "내 아이 기록이 다른 기관에 가는 것" 을 막는 것이다. 보호자 본인은 계속 본다. */
    @Test
    void 기관_동의를_철회하면_기관_권한만_거두고_보호자_권한은_둔다() {
        Long toCenter = grant(fromSchoolAndCenter, ReceiverType.ORGANIZATION, CENTER);
        Long toGuardian = grant(fromSchoolAndCenter, ReceiverType.GUARDIAN, GUARDIAN);

        revoker.revokeForInstitution(CHILD, CENTER, ConsentRevokeReason.GUARDIAN_REVOKED, AT);

        assertThat(reload(toCenter).isActive()).isFalse();
        assertThat(reload(toCenter).getRevokedAt()).isEqualTo(AT);
        assertThat(reload(toCenter).getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
        assertThat(reload(toGuardian).isActive()).isTrue();
    }

    /** 그 기관 기록이 안 섞인 인사이트는 건드리지 않는다. */
    @Test
    void 재료로_쓰이지_않은_인사이트의_권한은_그대로다() {
        Long untouched = grant(fromSchoolOnly, ReceiverType.ORGANIZATION, CENTER);

        revoker.revokeForInstitution(CHILD, CENTER, ConsentRevokeReason.GUARDIAN_REVOKED, AT);

        assertThat(reload(untouched).isActive()).isTrue();
    }

    @Test
    void 다른_아이의_권한은_건드리지_않는다() {
        Long otherChildInsight = insight(OTHER_CHILD, 1, SCHOOL, CENTER);
        Long otherChildGrant = grant(OTHER_CHILD, otherChildInsight, ReceiverType.ORGANIZATION, CENTER);

        revoker.revokeForInstitution(CHILD, CENTER, ConsentRevokeReason.GUARDIAN_REVOKED, AT);

        assertThat(reload(otherChildGrant).isActive()).isTrue();
    }

    @Test
    void 연결이_끊긴_보호자의_권한은_전부_거둔다() {
        Long mine = grant(fromSchoolAndCenter, ReceiverType.GUARDIAN, GUARDIAN);
        Long alsoMine = grant(fromSchoolOnly, ReceiverType.GUARDIAN, GUARDIAN);
        Long others = grant(fromSchoolAndCenter, ReceiverType.GUARDIAN, OTHER_GUARDIAN);

        revoker.revokeForGuardian(CHILD, GUARDIAN, ConsentRevokeReason.GUARDIAN_UNLINKED, AT);

        assertThat(reload(mine).isActive()).isFalse();
        assertThat(reload(alsoMine).isActive()).isFalse();
        assertThat(reload(others).isActive()).isTrue();
    }

    /** 거둔 권한은 다시 안 집는다 — 조회가 살아 있는 것만 가져온다. */
    @Test
    void 이미_거둔_권한은_다시_집지_않는다() {
        Long toCenter = grant(fromSchoolAndCenter, ReceiverType.ORGANIZATION, CENTER);
        revoker.revokeForInstitution(CHILD, CENTER, ConsentRevokeReason.GUARDIAN_REVOKED, AT);

        revoker.revokeForInstitution(CHILD, CENTER, ConsentRevokeReason.ROSTER_REMOVED, AT.plusDays(1));

        assertThat(reload(toCenter).getRevokedAt()).isEqualTo(AT);
        assertThat(reload(toCenter).getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
    }

    /**
     * 엔티티가 마지막으로 한 번 더 막는다. 지금은 조회가 살아 있는 행만 주므로 이 길로 안 오지만,
     * 두 경로가 같은 행을 가리킬 수 있어 덮어쓰면 <b>처음 거둔 시각과 사유가 사라진다.</b>
     */
    @Test
    void 엔티티는_두_번째_철회를_무시한다() {
        SharingHistory history = SharingHistory.grant(CHILD, fromSchoolAndCenter, SCHOOL,
                ReceiverType.ORGANIZATION, CENTER);

        history.revoke(ConsentRevokeReason.GUARDIAN_REVOKED, AT);
        history.revoke(ConsentRevokeReason.ROSTER_REMOVED, AT.plusDays(1));

        assertThat(history.getRevokedAt()).isEqualTo(AT);
        assertThat(history.getRevokeReason()).isEqualTo(ConsentRevokeReason.GUARDIAN_REVOKED);
    }

    /** AI 가 쓴 JSON 이 깨져 있어도 철회가 500 나면 안 된다 — 다만 그 인사이트는 못 찾는다. */
    @Test
    void 근거_기관을_읽을_수_없는_인사이트는_건너뛴다() {
        Long broken = em.persistAndFlush(InsightResult.of(CHILD, FROM, TO, 3, "상황", "반응", "본문",
                null, "[]", "[]", "{깨진 JSON", false, null)).getId();
        Long grant = grant(broken, ReceiverType.ORGANIZATION, CENTER);

        revoker.revokeForInstitution(CHILD, CENTER, ConsentRevokeReason.GUARDIAN_REVOKED, AT);

        assertThat(reload(grant).isActive()).isTrue();
    }

    /**
     * 거둔 권한은 같은 행을 되살린다 — 새로 넣으면 {@code (insight, receiver_type, receiver_id)} UNIQUE 에
     * 걸린다. 동의를 다시 켜거나 보호자가 다시 연결되는 길이 여기로 돌아온다.
     */
    @Test
    void 되살리면_다시_볼_수_있고_거둔_흔적은_지워진다() {
        SharingHistory history = SharingHistory.grant(CHILD, fromSchoolAndCenter, SCHOOL,
                ReceiverType.ORGANIZATION, CENTER);
        history.revoke(ConsentRevokeReason.GUARDIAN_REVOKED, AT);

        history.regrant(AT.plusDays(1));

        assertThat(history.isActive()).isTrue();
        assertThat(history.getRevokedAt()).isNull();
        assertThat(history.getRevokeReason()).isNull();
        assertThat(history.getGrantedAt()).isEqualTo(AT.plusDays(1));
    }

    /** 살아 있는 권한에 다시 부르면 "언제부터 볼 수 있었나" 가 뒤로 밀리면 안 된다. */
    @Test
    void 살아_있는_권한을_다시_열면_아무것도_바뀌지_않는다() {
        SharingHistory history = SharingHistory.grant(CHILD, fromSchoolAndCenter, SCHOOL,
                ReceiverType.GUARDIAN, GUARDIAN);
        LocalDateTime granted = history.getGrantedAt();

        history.regrant(AT.plusDays(1));

        assertThat(history.getGrantedAt()).isEqualTo(granted);
    }

    private Long insight(Long childId, int revision, Long... sourceInstitutionIds) {
        String json = "[" + String.join(",",
                java.util.Arrays.stream(sourceInstitutionIds).map(String::valueOf).toList()) + "]";
        Long id = em.persistAndFlush(InsightResult.of(childId, FROM, TO, revision, "급식실 입장 직후",
                "소리를 지름", "이동 전 5분 예고가 효과적이었습니다.", null, "[]", "[]", json,
                sourceInstitutionIds.length > 1, null)).getId();
        em.clear();
        return id;
    }

    private Long grant(Long insightId, ReceiverType receiverType, Long receiverId) {
        return grant(CHILD, insightId, receiverType, receiverId);
    }

    private Long grant(Long childId, Long insightId, ReceiverType receiverType, Long receiverId) {
        Long id = em.persistAndFlush(
                SharingHistory.grant(childId, insightId, SCHOOL, receiverType, receiverId)).getId();
        em.clear();
        return id;
    }

    private SharingHistory reload(Long id) {
        em.flush();
        em.clear();
        return sharingHistoryRepository.findById(id).orElseThrow();
    }
}
