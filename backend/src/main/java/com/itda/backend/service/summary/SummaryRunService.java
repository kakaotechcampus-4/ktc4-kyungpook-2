package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.exception.summary.SummaryRunValidationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SummaryRunService {

    private final UserService userService;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final SummaryRunRequests runRequests;
    private final ConsentStateReader consentStateReader;
    private final Clock clock;

    public void requestRun(String userId, Long childId, LocalDate entryDate) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
            throw new SummaryChildNotFoundException(childId);
        }
        if (consentStateReader.stateOf(childId, organizationId) != ConsentState.GRANTED) {
            throw new SummaryChildNotFoundException(childId);
        }
        String institutionId = String.valueOf(organizationId);
        if (!journalEntryRepository.findGroupEntryIds(
                JournalEntryStatus.SUMMARIZING, childId, entryDate, institutionId).isEmpty()) {
            throw new SummaryException(SummaryErrorCode.IN_PROGRESS,
                    "summarizing childId=" + childId + " entryDate=" + entryDate);
        }
        boolean hasNewEntry = !journalEntryRepository.findGroupEntryIds(
                JournalEntryStatus.VALIDATED, childId, entryDate, institutionId).isEmpty();
        boolean stillProcessing = journalEntryRepository.existsInProgress(
                SummaryService.IN_PROGRESS, institutionId, entryDate);
        if (!hasNewEntry && !stillProcessing) {
            throw new SummaryRunValidationException(
                    "no journal entry to summarize childId=" + childId + " entryDate=" + entryDate);
        }
        SummaryGroup group = new SummaryGroup(childId, entryDate, organizationId);
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            runRequests.request(group, now);
        } catch (DataIntegrityViolationException e) {
            if (!isViolationOf(e, SummaryRunRequest.UK_GROUP)) {
                throw e;
            }
            runRequests.request(group, now);
        }
    }

    private static boolean isViolationOf(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
