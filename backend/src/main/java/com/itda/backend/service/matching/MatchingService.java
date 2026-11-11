package com.itda.backend.service.matching;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.exception.agent.AgentContentTooLongException;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.dto.request.matching.MatchingAgentRequest;
import com.itda.backend.exception.matching.MatchingTargetException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchingService {

    private final JournalEntryRepository journalEntryRepository;
    private final RawRecordRepository rawRecordRepository;
    private final OrganizationRepository organizationRepository;
    private final ChildRepository childRepository;

    @Transactional
    public List<Long> claimPending(int batchSize) {
        List<JournalEntry> targets = journalEntryRepository.findMatchingTargets(
                JournalEntryStatus.PENDING, Limit.of(batchSize));
        targets.forEach(JournalEntry::startMatching);
        return targets.stream().map(JournalEntry::getId).toList();
    }

    @Transactional(readOnly = true)
    public MatchingAgentRequest prepareRequest(Long journalEntryId) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId)
                .orElseThrow(() -> new MatchingTargetException("journal entry not found: " + journalEntryId));
        if (!entry.isSendableToAgent()) {
            throw new AgentContentTooLongException(journalEntryId, entry.getContent().length());
        }
        RawRecord rawRecord = rawRecordRepository.findByIdAndDeletedAtIsNull(entry.getRawRecordId())
                .orElseThrow(() -> new MatchingTargetException("raw record not found: " + entry.getRawRecordId()));
        Long organizationId = organizationIdOf(rawRecord);

        List<MatchingAgentRequest.RosterEntry> roster = childRepository.findConsentedByOrganizationId(organizationId)
                .stream()
                .map(child -> new MatchingAgentRequest.RosterEntry(
                        child.getId(), child.getName(), child.getBirthdate().toString()))
                .toList();

        return new MatchingAgentRequest(
                entry.getId(),
                entry.getContent(),
                roster,
                rawRecord.getId(),
                toText(entry.getEntryDate()),
                rawRecord.getHintName(),
                toText(rawRecord.getHintBirthdate()));
    }

    @Transactional
    public void release(List<Long> journalEntryIds) {
        journalEntryRepository.findAllById(journalEntryIds).stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.MATCHING)
                .forEach(JournalEntry::releaseMatching);
    }

    @Transactional
    public boolean releaseAfterAgentDown(Long journalEntryId, int maxReleases) {
        JournalEntry entry = journalEntryRepository.findById(journalEntryId)
                .filter(found -> found.getStatus() == JournalEntryStatus.MATCHING)
                .orElse(null);
        if (entry == null) {
            return true;
        }
        if (entry.getAgentRetryCount() >= maxReleases) {
            return false;
        }
        entry.releaseMatchingAfterAgentDown();
        return true;
    }

    @Transactional
    public int releaseStuck() {
        List<JournalEntry> stuck = journalEntryRepository.findByStatusAndDeletedAtIsNull(JournalEntryStatus.MATCHING);
        stuck.forEach(JournalEntry::releaseMatching);
        return stuck.size();
    }

    private Long organizationIdOf(RawRecord rawRecord) {
        Long organizationId;
        try {
            organizationId = Long.valueOf(rawRecord.getInstitutionId());
        } catch (NumberFormatException e) {
            throw new MatchingTargetException("raw record has invalid institution id: " + rawRecord.getId());
        }
        if (!organizationRepository.existsById(organizationId)) {
            throw new MatchingTargetException("organization not found for raw record: " + rawRecord.getId());
        }
        return organizationId;
    }

    private static String toText(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
