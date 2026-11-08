package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.organization.OrganizationType;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.exception.agent.AgentQueueFullException;

/** 기관별 AI 처리 줄 상한 (#424). 세는 범위와 트랜잭션 조건을 본다. 동시 요청 직렬화는 통합 시험이 본다. */
@DataJpaTest
@ActiveProfiles("test")
@Import(AgentQueueGuardTest.Config.class)
@TestPropertySource(properties = "app.agent-queue.max-awaiting-entries=6")
class AgentQueueGuardTest {

    @TestConfiguration
    @EnableConfigurationProperties(AgentQueueProperties.class)
    @Import(AgentQueueGuard.class)
    static class Config {
    }

    @Autowired
    private AgentQueueGuard guard;

    @Autowired
    private TestEntityManager em;

    private Long org;
    private Long otherOrg;
    private Long rawRecordId;

    @BeforeEach
    void setUp() {
        org = em.persist(Organization.of("햇살센터", OrganizationType.CENTER, "1000000001")).getId();
        otherOrg = em.persist(Organization.of("○○초등학교", OrganizationType.SCHOOL, "1000000002")).getId();
        rawRecordId = rawRecord(org);
    }

    @Test
    void AI_를_기다리거나_처리_중인_여섯_상태만_센다() {
        for (JournalEntryStatus status : AgentQueueGuard.AWAITING_AGENT) {
            entry(rawRecordId, status);
        }
        // 사람을 기다리거나 끝난 일지는 세지 않는다.
        for (JournalEntryStatus status : EnumSet.complementOf(EnumSet.copyOf(AgentQueueGuard.AWAITING_AGENT))) {
            entry(rawRecordId, status);
        }
        // 지운 일지, 다른 기관 일지도 세지 않는다.
        entry(rawRecordId, JournalEntryStatus.PENDING).delete();
        entry(rawRecord(otherOrg), JournalEntryStatus.PENDING);
        em.flush();

        assertThat(AgentQueueGuard.AWAITING_AGENT).containsExactlyInAnyOrder(
                JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING, JournalEntryStatus.MATCHED,
                JournalEntryStatus.VALIDATING, JournalEntryStatus.VALIDATED, JournalEntryStatus.SUMMARIZING);
        // 상한 6 에 여섯 건이 있으니 한 건도 더 들어올 수 없다.
        assertThatThrownBy(() -> guard.requireRoom(org, 1)).isInstanceOf(AgentQueueFullException.class);
        assertThatThrownBy(() -> guard.peek(org, 1)).isInstanceOf(AgentQueueFullException.class);
        // 다른 기관은 비어 있다.
        assertThatCode(() -> guard.requireRoom(otherOrg, 5)).doesNotThrowAnyException();
    }

    @Test
    void 상한까지는_받고_넘으면_거절한다() {
        entry(rawRecordId, JournalEntryStatus.PENDING);
        entry(rawRecordId, JournalEntryStatus.VALIDATED);
        em.flush();

        assertThatCode(() -> guard.requireRoom(org, 4)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireRoom(org, 5)).isInstanceOf(AgentQueueFullException.class);
    }

    /** 글자를 못 뽑은 업로드처럼 들어올 일지가 없으면 잠그지도 세지도 않는다 — 줄이 꽉 차도 원본은 받는다. */
    @Test
    void 들어올_일지가_없으면_줄이_꽉_차도_통과한다() {
        for (int i = 0; i < 6; i++) {
            entry(rawRecordId, JournalEntryStatus.PENDING);
        }
        em.flush();

        assertThatCode(() -> guard.requireRoom(org, 0)).doesNotThrowAnyException();
        assertThatCode(() -> guard.peek(org, 0)).doesNotThrowAnyException();
    }

    private Long rawRecord(Long organizationId) {
        return em.persist(new RawRecord(String.valueOf(organizationId), "note.txt", "kept/note.txt", "text/plain", 10,
                RawRecordStatus.PENDING)).getId();
    }

    private JournalEntry entry(Long rawRecordId, JournalEntryStatus status) {
        JournalEntry entry = JournalEntry.of(rawRecordId, LocalDate.of(2026, 10, 8), "관찰 문장", 1);
        ReflectionTestUtils.setField(entry, "status", status);
        return em.persist(entry);
    }
}
