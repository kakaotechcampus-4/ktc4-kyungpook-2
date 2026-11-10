package com.itda.backend.service.record;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.record.FailureStage;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.record.RecordProgressResponse;
import com.itda.backend.dto.response.record.RecordProgressResponse.EntryProgress;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.summary.SummaryProperties;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecordProgressService {

    private static final int STAGE_UPLOADED = 0;
    private static final int STAGE_MATCHING = 1;
    private static final int STAGE_VALIDATING = 2;
    private static final int STAGE_SUMMARIZING = 3;
    private static final int STAGE_GATE1 = 4;

    private static final Limit RECENT_FILES = Limit.of(50);

    private static final String RUNNING = "running";
    private static final String WAITING = "waiting";
    private static final String FAILED = "failed";
    private static final String DONE = "done";

    private final UserService userService;
    private final RawRecordRepository rawRecordRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final ValidationResultRepository validationResultRepository;
    private final SummaryProperties summaryProperties;

    @Transactional(readOnly = true)
    public List<RecordProgressResponse> getProgress(String userId) {
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));
        List<RawRecord> records =
                rawRecordRepository.findByInstitutionIdAndDeletedAtIsNullOrderByIdDesc(institutionId, RECENT_FILES);
        if (records.isEmpty()) {
            return List.of();
        }

        List<JournalEntry> entries = journalEntryRepository
                .findByRawRecordIdInAndDeletedAtIsNullOrderByIdAsc(records.stream().map(RawRecord::getId).toList());
        Map<Long, List<JournalEntry>> entriesByRecord =
                entries.stream().collect(Collectors.groupingBy(JournalEntry::getRawRecordId));
        Set<Long> validatedIds = validatedAmong(entries);

        return records.stream()
                .map(record -> new RecordProgressResponse(
                        String.valueOf(record.getId()),
                        record.getOriginalFilename(),
                        record.getCreatedAt(),
                        entriesByRecord.getOrDefault(record.getId(), List.of()).stream()
                                .map(entry -> progressOf(entry, validatedIds))
                                .toList()))
                .toList();
    }

    private Set<Long> validatedAmong(List<JournalEntry> entries) {
        List<Long> failedIds = entries.stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.FAILED)
                .map(JournalEntry::getId)
                .toList();
        if (failedIds.isEmpty()) {
            return Set.of();
        }
        return validationResultRepository.findByJournalEntryIdInOrderByIdDesc(failedIds).stream()
                .filter(result -> result.getVerdict() != ValidationVerdict.FAILED)
                .map(ValidationResult::getJournalEntryId)
                .collect(Collectors.toSet());
    }

    private EntryProgress progressOf(JournalEntry entry, Set<Long> validatedIds) {
        return new EntryProgress(String.valueOf(entry.getId()), stageOf(entry, validatedIds),
                stateOf(entry.getStatus(), summaryProperties.autoRun()));
    }

    private static int stageOf(JournalEntry entry, Set<Long> validatedIds) {
        return switch (entry.getStatus()) {
            case PENDING -> STAGE_UPLOADED;
            case MATCHING, MATCH_REVIEW, CONSENT_BLOCKED, EXCLUDED -> STAGE_MATCHING;
            case MATCHED, VALIDATING, VALIDATION_BLOCKED, REUPLOAD_REQUESTED, VALIDATION_HELD -> STAGE_VALIDATING;
            case VALIDATED, SUMMARIZING -> STAGE_SUMMARIZING;
            case GATE1_PENDING, GATE1_HELD -> STAGE_GATE1;
            case COMPLETED -> entry.getSummaryId() == null ? STAGE_SUMMARIZING : STAGE_GATE1;
            case SUMMARY_FAILED -> STAGE_SUMMARIZING;
            case SYSTEM_ERROR -> switch (entry.getFailureStage()) {
                case SUMMARY -> STAGE_SUMMARIZING;
                case MATCHING -> STAGE_MATCHING;
                case VALIDATION -> STAGE_VALIDATING;
                case null -> STAGE_VALIDATING;
            };
            case FAILED -> failedStageOf(entry, validatedIds);
        };
    }

    private static int failedStageOf(JournalEntry entry, Set<Long> validatedIds) {
        if (validatedIds.contains(entry.getId())) {
            return STAGE_SUMMARIZING;
        }
        return entry.getChildId() != null ? STAGE_VALIDATING : STAGE_MATCHING;
    }

    private static String stateOf(JournalEntryStatus status, boolean autoRun) {
        if (status == JournalEntryStatus.VALIDATED) {
            return autoRun ? RUNNING : WAITING;
        }
        return switch (status) {
            case PENDING, MATCHING, MATCHED, VALIDATING, SUMMARIZING -> RUNNING;
            case MATCH_REVIEW, CONSENT_BLOCKED, VALIDATION_BLOCKED, VALIDATED, GATE1_PENDING, GATE1_HELD -> WAITING;
            case EXCLUDED, REUPLOAD_REQUESTED, VALIDATION_HELD, COMPLETED -> DONE;
            case FAILED, SUMMARY_FAILED, SYSTEM_ERROR -> FAILED;
        };
    }
}
