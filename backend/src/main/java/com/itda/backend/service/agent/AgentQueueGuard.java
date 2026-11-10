package com.itda.backend.service.agent;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AgentQueueGuard {

    public static final Set<JournalEntryStatus> AWAITING_AGENT = EnumSet.of(
            JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING,
            JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING,
            JournalEntryStatus.VALIDATED, JournalEntryStatus.SUMMARIZING);

    private final OrganizationRepository organizationRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AgentQueueProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoom(Long organizationId, int incoming) {
        if (incoming <= 0) {
            return;
        }
        organizationRepository.lockOwnOrganization(organizationId);
        check(organizationId, incoming);
    }

    @Transactional(readOnly = true)
    public void peek(Long organizationId, int incoming) {
        if (incoming <= 0) {
            return;
        }
        check(organizationId, incoming);
    }

    private void check(Long organizationId, int incoming) {
        long awaiting = journalEntryRepository.countByInstitutionAndStatusIn(
                String.valueOf(organizationId), AWAITING_AGENT);
        if (awaiting + incoming > properties.maxAwaitingEntries()) {
            throw new AgentQueueFullException(organizationId, awaiting, incoming, properties.maxAwaitingEntries());
        }
    }
}
