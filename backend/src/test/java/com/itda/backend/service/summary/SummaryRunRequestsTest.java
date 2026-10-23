package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.repository.summary.SummaryRunRequestRepository;

/** 교사가 누른 [요약] 요청 — 버튼이 요약의 유일한 시작점이라 재시작해도 남아야 한다 (#181). */
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
        // 행은 지우지 않는다 (DB 스키마 §3.2). 어디까지 처리했는지만 남긴다.
        em.flush();
        em.clear();
        assertThat(repository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getRequestedAt()).isEqualTo(PRESSED);
            assertThat(saved.getHandledUntil()).isEqualTo(claimedAt);
        });
    }

    @Test
    void 집은_뒤에_다시_누른_요청은_결과를_남겨도_요청으로_남는다() {
        // 요약하는 사이 새 일지가 들어와 교사가 다시 눌렀다. 그 일지는 지금 요약에 들어 있지 않다.
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
    void 요약할_게_없게_된_요청은_시각과_상관없이_거둔다() {
        runRequests.request(GROUP, PRESSED);

        runRequests.cancel(GROUP);

        assertThat(runRequests.isRequested(GROUP)).isFalse();
        assertThat(runRequests.requestedGroups()).isEmpty();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 아직_처리하지_않은_요청의_묶음만_알려준다() {
        SummaryGroup other = new SummaryGroup(9L, GROUP.entryDate(), 3L);
        SummaryGroup handled = new SummaryGroup(10L, GROUP.entryDate(), 3L);
        runRequests.request(GROUP, PRESSED);
        runRequests.request(other, PRESSED);
        runRequests.request(handled, PRESSED);
        runRequests.markHandled(handled, PRESSED.plusMinutes(1));

        assertThat(runRequests.requestedGroups()).containsExactlyInAnyOrder(GROUP, other);
    }
}
