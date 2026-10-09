package com.itda.backend.service.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.dto.request.SummaryAgentRequest;
import com.itda.backend.exception.SummaryTargetException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.service.agent.WorkerProperties;

/** 요약 요청 조립 — 묶음의 일지들, 아동·기관 이름, 본문에 이름이 나온 다른 아이들. */
@DataJpaTest
@ActiveProfiles("test")
class SummaryServicePrepareTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private MatchingResultRepository matchingResultRepository;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private SummaryService summaryService;
    private Organization school;
    private Long rawRecordId;
    private Child junho;
    private Child seojun;
    private Child minji;

    @BeforeEach
    void setUp() {
        summaryService = new SummaryService(journalEntryRepository, matchingResultRepository, childRepository,
                organizationRepository, new SummaryRunRequests(),
                new SummaryProperties(new WorkerProperties(true, 5000, 10), LocalTime.of(3, 0),
                        Duration.ofMinutes(30), ZoneId.of("Asia/Seoul"), Duration.ofSeconds(120)),
                Clock.systemDefaultZone());
        school = em.persist(Organization.of("햇살초등학교", OrganizationType.SCHOOL, "1234567890"));
        rawRecordId = em.persist(new RawRecord(String.valueOf(school.getId()), "school.pdf", "kept/school.pdf",
                "application/pdf", 12, RawRecordStatus.PENDING)).getId();
        junho = em.persist(Child.of("김준호", LocalDate.of(2019, 3, 1)));
        seojun = em.persist(Child.of("서준호", LocalDate.of(2019, 5, 1)));
        minji = em.persist(Child.of("박민지", LocalDate.of(2019, 7, 1)));
    }

    private JournalEntry summarizing(String content, int seq, Long... mentioned) {
        JournalEntry entry = JournalEntry.of(rawRecordId, DATE, content, seq);
        entry.startMatching();
        entry.confirmMatch(junho.getId());
        entry.startValidating();
        entry.passValidation();
        entry.startSummarizing();
        em.persistAndFlush(entry);
        em.persistAndFlush(matchingResult(entry.getId(), mentioned));
        return entry;
    }

    private MatchingResult matchingResult(Long journalEntryId, Long... mentioned) {
        String ids = "[" + String.join(",", Arrays.stream(mentioned).map(String::valueOf).toList()) + "]";
        return new MatchingResult(journalEntryId, junho.getId(), null, MatchingStatus.AUTO, null, false,
                "[]", "[]", ids, "{}", null);
    }

    private ClaimedSummaryGroup group(JournalEntry... entries) {
        return new ClaimedSummaryGroup(new SummaryGroup(junho.getId(), DATE, school.getId()),
                Arrays.stream(entries).map(JournalEntry::getId).toList());
    }

    @Test
    void 요청에는_묶음의_일지들과_아동_기관_정보가_실린다() {
        JournalEntry first = summarizing("김준호가 블록을 쌓았다.", 1, junho.getId());
        JournalEntry second = summarizing("김준호가 점심을 남김없이 먹었다.", 2, junho.getId());

        SummaryAgentRequest request = summaryService.prepareRequest(group(first, second));

        assertThat(request.childId()).isEqualTo(junho.getId());
        assertThat(request.childName()).isEqualTo("김준호");
        assertThat(request.entryDate()).isEqualTo("2026-10-08");
        assertThat(request.institutionId()).isEqualTo(school.getId());
        assertThat(request.institutionName()).isEqualTo("햇살초등학교");
        assertThat(request.sources()).containsExactly(
                new SummaryAgentRequest.Source(first.getId(), "김준호가 블록을 쌓았다.", "2026-10-08"),
                new SummaryAgentRequest.Source(second.getId(), "김준호가 점심을 남김없이 먹었다.", "2026-10-08"));
    }

    @Test
    void 본문에_이름이_나온_다른_아이들을_합쳐서_주인공을_빼고_싣는다() {
        JournalEntry first = summarizing("김준호가 서준호와 블록을 쌓았다.", 1, junho.getId(), seojun.getId());
        JournalEntry second = summarizing("김준호가 박민지, 서준호와 점심을 먹었다.", 2,
                junho.getId(), minji.getId(), seojun.getId());

        SummaryAgentRequest request = summaryService.prepareRequest(group(first, second));

        assertThat(request.otherChildNames()).containsExactlyInAnyOrder("서준호", "박민지");
    }

    @Test
    void 명부에서_빠진_아이도_이름이_새면_안_되므로_싣는다() {
        seojun.delete();
        em.persistAndFlush(seojun);
        JournalEntry entry = summarizing("김준호가 서준호와 블록을 쌓았다.", 1, junho.getId(), seojun.getId());

        SummaryAgentRequest request = summaryService.prepareRequest(group(entry));

        assertThat(request.otherChildNames()).containsExactly("서준호");
    }

    @Test
    void 다른_아이가_나오지_않으면_빈_목록이다() {
        JournalEntry entry = summarizing("김준호가 블록을 쌓았다.", 1, junho.getId());

        assertThat(summaryService.prepareRequest(group(entry)).otherChildNames()).isEmpty();
    }

    @Test
    void 매칭_결과가_여러_번_쌓였으면_가장_최근_것을_쓴다() {
        JournalEntry entry = summarizing("김준호가 블록을 쌓았다.", 1, junho.getId(), minji.getId());
        em.persistAndFlush(matchingResult(entry.getId(), junho.getId(), seojun.getId()));

        assertThat(summaryService.prepareRequest(group(entry)).otherChildNames()).containsExactly("서준호");
    }

    @Test
    void 그새_삭제된_일지는_빼고_남은_일지가_없으면_요청을_만들_수_없다() {
        JournalEntry kept = summarizing("김준호가 블록을 쌓았다.", 1, junho.getId());
        JournalEntry removed = summarizing("김준호가 점심을 먹었다.", 2, junho.getId());
        removed.delete();
        em.persistAndFlush(removed);

        assertThat(summaryService.prepareRequest(group(kept, removed)).sources())
                .extracting(SummaryAgentRequest.Source::journalEntryId).containsExactly(kept.getId());

        kept.delete();
        em.persistAndFlush(kept);
        assertThatThrownBy(() -> summaryService.prepareRequest(group(kept, removed)))
                .isInstanceOf(SummaryTargetException.class);
    }
}
