package com.itda.backend.service.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.record.RecordResponse;
import com.itda.backend.dto.response.validation.ValidationQueueItemResponse;
import com.itda.backend.exception.validation.ValidationResultErrorCode;
import com.itda.backend.exception.validation.ValidationResultNotFoundException;
import com.itda.backend.exception.validation.ValidationResultValidationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.matching.MatchingResultService;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.agent.AgentQueueGuard;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationQueueService {

    private final ValidationResultRepository validationResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final RawRecordRepository rawRecordRepository;
    private final ChildRepository childRepository;
    private final UserService userService;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;
    private final AgentQueueGuard agentQueueGuard;

    @Transactional(readOnly = true)
    public List<ValidationQueueItemResponse> getBlockedQueue(String userId) {
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));
        return validationResultRepository.findByVerdictOrderByIdDesc(ValidationVerdict.BLOCK).stream()
                .collect(Collectors.toMap(
                        ValidationResult::getJournalEntryId,
                        result -> result,
                        (latest, older) -> latest,
                        LinkedHashMap::new))
                .values().stream()
                .flatMap(result -> resolveOwnedContext(result.getJournalEntryId(), institutionId)
                        .filter(ctx -> ctx.entry().isAwaitingReinput())
                        .map(ctx -> buildResponse(result, ctx))
                        .stream())
                .toList();
    }

    @Transactional
    public void resolve(Long id, String action, String userId) {
        if (!"hold".equals(action)) {
            throw new ValidationResultValidationException("unsupported action for validation result: " + id);
        }
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));
        ValidationResult result = findResult(id);
        requireOwnedBlock(result, institutionId);

        JournalEntry entry = lockAwaitingEntry(result);
        entry.holdAfterValidation();
        log.info("validation result resolved id={} action={} journalEntryId={}", id, action, entry.getId());
    }

    @Transactional
    public void revise(Long id, String content, String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        ValidationResult result = findResult(id);
        requireOwnedBlock(result, String.valueOf(organizationId));

        Long childId = result.getChildId();
        if (childId == null) {
            throw new IllegalStateException("blocked validation result has no child: " + id);
        }
        Optional<Child> child = childRepository.findByIdForUpdate(childId);
        JournalEntry entry = lockAwaitingEntry(result);
        if (!childId.equals(entry.getChildId())) {
            throw new IllegalStateException("blocked journal entry child differs from its validation result: " + id);
        }
        requireStillShared(child, childId, organizationId);
        if (entry.hasSameContent(content)) {
            throw new ValidationResultValidationException(ValidationResultErrorCode.CONTENT_UNCHANGED,
                    "revised content is unchanged: " + id);
        }
        agentQueueGuard.requireRoom(organizationId, 1);
        try {
            entry.revise(content, userId);
        } catch (IllegalArgumentException e) {
            throw new ValidationResultValidationException("invalid revised content: " + e.getMessage());
        }
        log.info("blocked journal entry revised id={} journalEntryId={}", id, entry.getId());
    }

    private ValidationResult findResult(Long id) {
        return validationResultRepository.findById(id)
                .orElseThrow(() -> new ValidationResultNotFoundException(id));
    }

    private void requireOwnedBlock(ValidationResult result, String institutionId) {
        if (!journalEntryRepository.existsOwnedBy(result.getJournalEntryId(), institutionId)) {
            throw new ValidationResultNotFoundException(result.getId());
        }
        if (result.getVerdict() != ValidationVerdict.BLOCK) {
            throw new ValidationResultValidationException("validation result is not blocked: " + result.getVerdict());
        }
    }

    private JournalEntry lockAwaitingEntry(ValidationResult result) {
        Long id = result.getId();
        JournalEntry entry = journalEntryRepository.findByIdForUpdate(result.getJournalEntryId())
                .orElseThrow(() -> new ValidationResultNotFoundException(id));
        if (!entry.isAwaitingReinput()) {
            throw new ValidationResultValidationException(
                    "journal entry is not awaiting reinput: " + entry.getStatus());
        }
        Long latestId = validationResultRepository.findFirstByJournalEntryIdOrderByIdDesc(result.getJournalEntryId())
                .map(ValidationResult::getId)
                .orElse(null);
        if (!id.equals(latestId)) {
            throw new ValidationResultValidationException(ValidationResultErrorCode.OUTDATED,
                    "validation result is outdated: " + id);
        }
        return entry;
    }

    private void requireStillShared(Optional<Child> child, Long childId, Long organizationId) {
        boolean available = child.filter(c -> !c.isDeleted()).isPresent()
                && childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)
                && consentStateReader.stateOf(childId, organizationId) == ConsentState.GRANTED;
        if (!available) {
            throw new ValidationResultValidationException(ValidationResultErrorCode.CHILD_NOT_SHARED,
                    "child not available for revalidation: " + childId);
        }
    }

    private record OwnedContext(JournalEntry entry, RawRecord rawRecord) {
    }

    private Optional<OwnedContext> resolveOwnedContext(Long journalEntryId, String institutionId) {
        return journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId)
                .filter(entry -> entry.getRawRecordId() != null)
                .flatMap(entry -> rawRecordRepository.findByIdAndDeletedAtIsNull(entry.getRawRecordId())
                        .filter(record -> record.getInstitutionId().equals(institutionId))
                        .map(record -> new OwnedContext(entry, record)));
    }

    private ValidationQueueItemResponse buildResponse(ValidationResult result, OwnedContext ctx) {
        RecordResponse record = RecordResponse.of(
                String.valueOf(ctx.rawRecord().getId()),
                ctx.rawRecord().getOriginalFilename(),
                ctx.entry().getContent(),
                ctx.entry().getEntryDate() == null ? null : ctx.entry().getEntryDate().toString());

        List<String> issueTypes = JsonColumns.readTexts(
                result.getIssueTypes(), "validation_result.issue_types", result.getId());
        String childName = result.getChildId() == null ? null
                : childRepository.findById(result.getChildId()).map(Child::getName).orElse(null);

        return new ValidationQueueItemResponse(
                String.valueOf(result.getId()),
                result.getJournalEntryId(),
                record,
                childName,
                describeViolation(issueTypes),
                issueTypes);
    }

    private static String describeViolation(List<String> issueTypes) {
        return String.join(" / ", ValidationReasons.sentencesOf(issueTypes));
    }
}
