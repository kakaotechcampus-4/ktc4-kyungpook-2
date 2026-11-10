package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.repository.summary.SummaryRunRequestRepository;

@DataJpaTest
@ActiveProfiles("test")
@Import(SummaryRunRequests.class)
class SummaryRunRequestsTest {

    private static final SummaryGroup GROUP = new SummaryGroup(8L, LocalDate.of(2026, 10, 8), 3L);
    private static final LocalDateTime PRESSED = LocalDateTime.of(2026, 10, 8, 17, 0);

    @Autowired
    private SummaryRunRequests runRequests;

    @Autowired
    private SummaryRunRequestRepository repository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 요청은_DB에_남는다() {
        runRequests.request(GROUP, PRESSED);
        em.clear();

        assertThat(repository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getChildId()).isEqualTo(8L);
            assertThat(saved.getEntryDate()).isEqualTo(GROUP.entryDate());
            assertThat(saved.getInstitutionId()).isEqualTo(3L);
            assertThat(saved.getRequestedAt()).isEqualTo(PRESSED);
        });
        assertThat(runRequests.isRequested(GROUP)).isTrue();
        assertThat(runRequests.isRequested(new SummaryGroup(9L, GROUP.entryDate(), 3L))).isFalse();
    }

    @Test
    void 두_번_눌러도_한_행이고_마지막으로_누른_시각이_남는다() {
        runRequests.request(GROUP, PRESSED);
        runRequests.request(GROUP, PRESSED.plusMinutes(5));
        em.flush();
        em.clear();

        assertThat(repository.findAll()).extracting(SummaryRunRequest::getRequestedAt)
                .containsExactly(PRESSED.plusMinutes(5));
    }

    @Test
    void 집기_전에_누른_요청은_결과를_남길_때_처리됨으로_남긴다() {
        LocalDateTime claimedAt = PRESSED.plusMinutes(1);
        runRequests.request(GROUP, PRESSED);

        runRequests.markHandled(GROUP, claimedAt);

        assertThat(runRequests.isRequested(GROUP)).isFalse();
        em.flush();
        em.clear();
        assertThat(repository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getRequestedAt()).isEqualTo(PRESSED);
            assertThat(saved.getHandledUntil()).isEqualTo(claimedAt);
        });
    }

    @Test
    void 집은_뒤에_다시_누른_요청은_결과를_남겨도_요청으로_남는다() {
        LocalDateTime claimedAt = PRESSED.plusMinutes(1);
        runRequests.request(GROUP, PRESSED);
        runRequests.request(GROUP, claimedAt.plusMinutes(1));

        runRequests.markHandled(GROUP, claimedAt);

        assertThat(runRequests.isRequested(GROUP)).isTrue();
    }

    @Test
    void 처리한_뒤_다시_누르면_다시_요청된다() {
        runRequests.request(GROUP, PRESSED);
        runRequests.markHandled(GROUP, PRESSED.plusMinutes(1));

        runRequests.request(GROUP, PRESSED.plusMinutes(10));

        assertThat(runRequests.isRequested(GROUP)).isTrue();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 늦게_끝난_옛_요약이_처리_시각을_되돌리지_않는다() {
        runRequests.request(GROUP, PRESSED);
        runRequests.markHandled(GROUP, PRESSED.plusMinutes(5));
        runRequests.request(GROUP, PRESSED.plusMinutes(3));

        runRequests.markHandled(GROUP, PRESSED.plusMinutes(2));

        assertThat(runRequests.isRequested(GROUP)).isFalse();
    }

    @Test
    void 늦게_도착한_옛_누름이_더_늦게_누른_시각을_되돌리지_않는다() {
        runRequests.request(GROUP, PRESSED.plusMinutes(3));
        runRequests.markHandled(GROUP, PRESSED.plusMinutes(2));

        runRequests.request(GROUP, PRESSED.plusMinutes(1));

        assertThat(runRequests.isRequested(GROUP)).isTrue();
    }

    @Test
    void 읽어_둔_요청을_거둘_때_그새_다시_누른_시각을_옛_값으로_덮지_않는다() {
        runRequests.request(GROUP, PRESSED);
        em.flush();
        em.clear();
        runRequests.pendingRequests();
        em.getEntityManager().createNativeQuery("update summary_run_request set requested_at = :t")
                .setParameter("t", PRESSED.plusMinutes(5))
                .executeUpdate();

        runRequests.markHandled(GROUP, PRESSED);
        em.flush();
        em.clear();

        assertThat(repository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getRequestedAt()).isEqualTo(PRESSED.plusMinutes(5));
            assertThat(saved.isPending()).isTrue();
        });
    }

    @Test
    void 아직_처리하지_않은_요청을_누른_시각과_함께_알려준다() {
        SummaryGroup other = new SummaryGroup(9L, GROUP.entryDate(), 3L);
        SummaryGroup handled = new SummaryGroup(10L, GROUP.entryDate(), 3L);
        runRequests.request(GROUP, PRESSED);
        runRequests.request(other, PRESSED.plusMinutes(1));
        runRequests.request(handled, PRESSED);
        runRequests.markHandled(handled, PRESSED.plusMinutes(1));

        assertThat(runRequests.pendingRequests()).containsExactlyInAnyOrderEntriesOf(
                Map.of(GROUP, PRESSED, other, PRESSED.plusMinutes(1)));
    }
}
