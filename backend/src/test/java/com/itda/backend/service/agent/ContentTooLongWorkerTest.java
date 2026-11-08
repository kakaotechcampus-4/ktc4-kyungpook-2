package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.FailureStage;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.matching.MatchingAgentClient;
import com.itda.backend.service.matching.MatchingProperties;
import com.itda.backend.service.matching.MatchingResultRecorder;
import com.itda.backend.service.matching.MatchingService;
import com.itda.backend.service.matching.MatchingWorker;
import com.itda.backend.service.validation.ValidationAgentClient;
import com.itda.backend.service.validation.ValidationProperties;
import com.itda.backend.service.validation.ValidationResultRecorder;
import com.itda.backend.service.validation.ValidationService;
import com.itda.backend.service.validation.ValidationWorker;

/**
 * 상한(#424) 전에 올라온 긴 일지는 AI 에 보내지 않고 시스템 오류로 멈춘다. 워커를 실제 서비스 · 레코더와 함께 돌리고, 각 단계가
 * 자기 트랜잭션에서 커밋한 뒤 <b>새로 읽어</b> 상태 · 단계 · 원인이 DB 에 남았는지 본다. 요청 조립은 읽기 전용이라 거기서 상태를
 * 바꾸면 저장되지 않는다 — 그 실수를 잡는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({MatchingService.class, MatchingResultRecorder.class, ValidationService.class, ValidationResultRecorder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContentTooLongWorkerTest {

    private static final String TOO_LONG = "가".repeat(JournalEntry.MAX_CONTENT_LENGTH + 1);

    @Autowired private MatchingService matchingService;
    @Autowired private MatchingResultRecorder matchingRecorder;
    @Autowired private ValidationService validationService;
    @Autowired private ValidationResultRecorder validationRecorder;
    @Autowired private JournalEntryRepository entries;
    @Autowired private MatchingResultRepository matchingResults;
    @Autowired private ValidationResultRepository validationResults;
    @Autowired private RawRecordRepository rawRecords;
    @Autowired private OrganizationRepository organizations;

    private final MatchingAgentClient matchingClient = mock(MatchingAgentClient.class);
    private final ValidationAgentClient validationClient = mock(ValidationAgentClient.class);
    private final AiAvailability availability = mock(AiAvailability.class);

    private MatchingWorker matchingWorker;
    private ValidationWorker validationWorker;

    @BeforeEach
    void setUp() {
        given(matchingClient.isAvailable()).willReturn(true);
        given(validationClient.isAvailable()).willReturn(true);
        AgentDownProperties agentDown = new AgentDownProperties(2, Duration.ofSeconds(30));
        WorkerProperties worker = new WorkerProperties(true, 5000, 10);
        matchingWorker = new MatchingWorker(matchingService, matchingClient, matchingRecorder,
                new MatchingProperties(worker), agentDown, Clock.systemDefaultZone(), availability);
        validationWorker = new ValidationWorker(validationService, validationClient, validationRecorder,
                new ValidationProperties(worker), agentDown, Clock.systemDefaultZone(), availability);
    }

    @AfterEach
    void cleanUp() {
        validationResults.deleteAll();
        matchingResults.deleteAll();
        entries.deleteAll();
        rawRecords.deleteAll();
        organizations.deleteAll();
    }

    @Test
    void 매칭_대기의_긴_일지는_AI_에_보내지_않고_매칭_단계_시스템_오류로_남긴다() {
        Long id = entries.save(JournalEntry.of(1L, LocalDate.of(2026, 10, 8), TOO_LONG, 1)).getId();

        matchingWorker.run();

        JournalEntry stopped = entries.findById(id).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo(JournalEntryStatus.SYSTEM_ERROR);
        assertThat(stopped.getFailureStage()).isEqualTo(FailureStage.MATCHING);
        assertThat(stopped.getFailureReason()).isEqualTo(FailureReason.CONTENT_TOO_LONG);
        verify(matchingClient, never()).match(any());
        // 확인 필요 큐(FAILED)로 보내지 않는다 — 결과 행이 없어야 큐에 뜨지 않는다.
        assertThat(matchingResults.findAll()).isEmpty();
    }

    /** 확인 필요 큐에서 아이를 골라 검증 대기로 들어온 긴 일지 (경로 ①). */
    @Test
    void 검증_대기의_긴_일지는_AI_에_보내지_않고_검증_단계_시스템_오류로_남긴다() {
        JournalEntry entry = JournalEntry.of(1L, LocalDate.of(2026, 10, 8), TOO_LONG, 1);
        entry.startMatching();
        entry.requestMatchReview();
        entry.confirmMatchByReviewer(8L);
        Long id = entries.save(entry).getId();

        validationWorker.run();

        JournalEntry stopped = entries.findById(id).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo(JournalEntryStatus.SYSTEM_ERROR);
        assertThat(stopped.getFailureStage()).isEqualTo(FailureStage.VALIDATION);
        assertThat(stopped.getFailureReason()).isEqualTo(FailureReason.CONTENT_TOO_LONG);
        verify(validationClient, never()).validate(any());
    }

    @Test
    void 길이_안의_일지는_지금처럼_AI_에_보낸다() {
        Long org = organizations.save(Organization.of("햇살센터", OrganizationType.CENTER, "1000000001")).getId();
        Long rawRecordId = rawRecords.save(new RawRecord(String.valueOf(org), "note.txt", "kept/note.txt", "text/plain",
                10, RawRecordStatus.PENDING)).getId();
        entries.save(JournalEntry.of(rawRecordId, LocalDate.of(2026, 10, 8),
                "가".repeat(JournalEntry.MAX_CONTENT_LENGTH), 1));

        matchingWorker.run();

        verify(matchingClient).match(any());
    }
}
