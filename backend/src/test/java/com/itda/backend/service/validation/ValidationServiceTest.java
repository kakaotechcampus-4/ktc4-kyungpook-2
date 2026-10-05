package com.itda.backend.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.dto.request.ValidationAgentRequest;
import com.itda.backend.exception.ValidationTargetException;

@DataJpaTest
@ActiveProfiles("test")
@Import(ValidationService.class)
class ValidationServiceTest {

    @Autowired
    private ValidationService validationService;

    @Autowired
    private TestEntityManager em;

    private Child yujin;

    @BeforeEach
    void setUp() {
        yujin = em.persist(Child.of("임유진", LocalDate.of(2019, 11, 26)));
    }

    private JournalEntry matched(Long childId) {
        JournalEntry entry = JournalEntry.of(3L, LocalDate.of(2026, 9, 1), "임유진이 블록을 높이 쌓았다.", 1);
        entry.startMatching();
        entry.confirmMatch(childId);
        return em.persistAndFlush(entry);
    }

    private JournalEntry validating(Long childId) {
        JournalEntry entry = matched(childId);
        entry.startValidating();
        return em.persistAndFlush(entry);
    }

    private JournalEntry reload(JournalEntry entry) {
        em.flush();
        em.clear();
        return em.find(JournalEntry.class, entry.getId());
    }

    @Test
    void 매칭이_확정된_일지를_집어_가면서_검증_중으로_바꾼다() {
        JournalEntry first = matched(yujin.getId());
        JournalEntry second = matched(yujin.getId());

        List<Long> claimed = validationService.claimMatched(10);

        assertThat(claimed).containsExactly(first.getId(), second.getId());
        assertThat(reload(first).getStatus()).isEqualTo(JournalEntryStatus.VALIDATING);
        assertThat(reload(second).getStatus()).isEqualTo(JournalEntryStatus.VALIDATING);
    }

    @Test
    void 요청에는_일지_내용과_매칭으로_확정된_아동이_실린다() {
        JournalEntry entry = validating(yujin.getId());

        ValidationTarget target = validationService.prepareRequest(entry.getId());

        assertThat(target.request()).isEqualTo(new ValidationAgentRequest(
                entry.getId(), "임유진이 블록을 높이 쌓았다.", yujin.getId(), "임유진"));
    }

    @Test
    void 입력으로_쓴_매칭_결과는_그_일지의_가장_최근_것이다() {
        JournalEntry entry = validating(yujin.getId());
        em.persist(MatchingResult.failed(entry.getId()));
        MatchingResult latest = em.persist(MatchingResult.failed(entry.getId()));

        assertThat(validationService.prepareRequest(entry.getId()).matchingResultId()).isEqualTo(latest.getId());
    }

    @Test
    void 매칭_결과가_없으면_비워_둔다() {
        JournalEntry entry = validating(yujin.getId());

        assertThat(validationService.prepareRequest(entry.getId()).matchingResultId()).isNull();
    }

    @Test
    void 아동을_찾을_수_없으면_이름을_비워_보낸다() {
        // AI 가 판정 대상을 몰라 REVIEW(대상불명확)로 돌려준다. 정상 경로로는 생기지 않는다.
        JournalEntry entry = validating(9999L);

        ValidationAgentRequest request = validationService.prepareRequest(entry.getId()).request();

        assertThat(request.subjectChildId()).isEqualTo(9999L);
        assertThat(request.subjectName()).isNull();
    }

    @Test
    void 삭제된_일지는_요청을_만들_수_없다() {
        JournalEntry entry = validating(yujin.getId());
        entry.delete();
        em.persistAndFlush(entry);

        assertThatThrownBy(() -> validationService.prepareRequest(entry.getId()))
                .isInstanceOf(ValidationTargetException.class);
    }

    @Test
    void 검증_중에_멈춘_일지를_검증_대기로_되돌린다() {
        JournalEntry stuck = validating(yujin.getId());
        JournalEntry untouched = matched(yujin.getId());

        int released = validationService.releaseStuck();

        assertThat(released).isEqualTo(1);
        assertThat(reload(stuck).getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(reload(untouched).getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
    }

    @Test
    void 집어_갔던_일지를_검증_대기로_돌려놓고_이미_처리된_일지는_건드리지_않는다() {
        JournalEntry claimed = validating(yujin.getId());
        JournalEntry done = validating(yujin.getId());
        done.passValidation();
        em.persistAndFlush(done);

        validationService.release(List.of(claimed.getId(), done.getId()));

        assertThat(reload(claimed).getStatus()).isEqualTo(JournalEntryStatus.MATCHED);
        assertThat(reload(done).getStatus()).isEqualTo(JournalEntryStatus.VALIDATED);
    }
}
