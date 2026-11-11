package com.itda.backend.service.validation;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.exception.agent.AgentContentTooLongException;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.dto.request.validation.ValidationAgentRequest;
import com.itda.backend.exception.validation.ValidationTargetException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.JournalEntryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ValidationService {

    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;
    private final ChildRepository childRepository;

    @Transactional
    public List<Long> claimMatched(int batchSize) {
        List<JournalEntry> targets = journalEntryRepository.findValidationTargets(
                JournalEntryStatus.MATCHED, Limit.of(batchSize));
        targets.forEach(JournalEntry::startValidating);
        return targets.stream().map(JournalEntry::getId).toList();
    }

    @Transactional(readOnly = true)
    public ValidationTarget prepareRequest(Long journalEntryId) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId)
                .orElseThrow(() -> new ValidationTargetException("journal entry not found: " + journalEntryId));
        if (!entry.isSendableToAgent()) {
            throw new AgentContentTooLongException(journalEntryId, entry.getContent().length());
        }
        Long childId = entry.getChildId();
        if (childId == null) {
            throw new ValidationTargetException("journal entry has no matched child: " + journalEntryId);
        }
        String childName = childRepository.findById(childId).map(Child::getName).orElse(null);
        Long matchingResultId = matchingResultRepository.findFirstByJournalEntryIdOrderByIdDesc(journalEntryId)
                .map(MatchingResult::getId)
                .orElse(null);

        return new ValidationTarget(
                new ValidationAgentRequest(entry.getId(), entry.getContent(), childId, childName),
                matchingResultId);
    }

    @Transactional
    public void release(List<Long> journalEntryIds) {
        journalEntryRepository.findAllById(journalEntryIds).stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.VALIDATING)
                .forEach(JournalEntry::releaseValidating);
    }

    @Transactional
    public boolean releaseAfterAgentDown(Long journalEntryId, int maxReleases) {
        JournalEntry entry = journalEntryRepository.findById(journalEntryId)
                .filter(found -> found.getStatus() == JournalEntryStatus.VALIDATING)
                .orElse(null);
        if (entry == null) {
            return true;
        }
        if (entry.getAgentRetryCount() >= maxReleases) {
            return false;
        }
        entry.releaseValidatingAfterAgentDown();
        return true;
    }

    @Transactional
    public int releaseStuck() {
        List<JournalEntry> stuck = journalEntryRepository.findByStatusAndDeletedAtIsNull(JournalEntryStatus.VALIDATING);
        stuck.forEach(JournalEntry::releaseValidating);
        return stuck.size();
    }
}
