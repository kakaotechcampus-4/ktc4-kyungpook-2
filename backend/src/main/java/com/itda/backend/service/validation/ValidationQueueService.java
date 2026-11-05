package com.itda.backend.service.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.record.RecordResponse;
import com.itda.backend.dto.response.validation.ValidationQueueItemResponse;
import com.itda.backend.exception.validation.ValidationResultNotFoundException;
import com.itda.backend.exception.validation.ValidationResultValidationException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.matching.MatchingResultService;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수정 요청 큐 — 검증에서 막힌(BLOCK) 기록을 선생님이 확인하고 처리한다 (docs/api/api-spec.md O-24, O-25).
 *
 * <p>검증 결과를 사람이 화면으로 볼 수 있는 유일한 경로다. 여기가 없으면 BLOCK 판정이 조용히 쌓이기만 한다.
 */
// backend/AGENTS.md: 조회는 @Transactional(readOnly = true), 변경은 @Transactional로 범위를 명시한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationQueueService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ValidationResultRepository validationResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final RawRecordRepository rawRecordRepository;
    private final ChildRepository childRepository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<ValidationQueueItemResponse> getBlockedQueue(String userId) {
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));
        // ponytail: 건별로 JournalEntry/RawRecord/Child 조회 — N+1이지만 지금 큐 규모에선 문제없다.
        // MatchingResultService.getQueue와 같은 판단이고, 커지면 join 쿼리로 바꾼다.
        return validationResultRepository.findByVerdictOrderByIdDesc(ValidationVerdict.BLOCK).stream()
                // 한 일지를 다시 검증하면 BLOCK 행이 쌓인다(§8.1 — 결과는 수정하지 않고 새로 쌓는다).
                // 같은 기록이 큐에 두 번 뜨면 선생님이 하나만 처리하고 나머지가 남으므로, 일지당
                // 가장 최근 판정 하나만 남긴다(id 내림차순이라 먼저 만난 것이 최신이다).
                .collect(Collectors.toMap(
                        ValidationResult::getJournalEntryId,
                        result -> result,
                        (latest, older) -> latest,
                        LinkedHashMap::new))
                .values().stream()
                .flatMap(result -> resolveOwnedContext(result.getJournalEntryId(), institutionId)
                        // 이미 처리된 일지(다른 선생님이 먼저 처리 등)는 큐에서 뺀다 — 눌러도 resolve가 거절한다.
                        .filter(ctx -> ctx.entry().isAwaitingReinput())
                        .map(ctx -> buildResponse(result, ctx))
                        .stream())
                .toList();
    }

    /**
     * 선생님이 막힌 기록을 보류한다. 이 일지의 파이프라인을 여기서 끝낸다.
     *
     * <p>{@code reupload}(고친 원본 다시 올리기)는 #357 부터 받지 않는다 — 본문을 바로 고치는 {@link #revise} 로 바뀌었다.
     */
    @Transactional
    public void resolve(Long id, String action, String userId) {
        JournalEntry entry = lockBlockedEntry(id, userId);

        if (!"hold".equals(action)) {
            throw new ValidationResultValidationException("unsupported action: " + action);
        }
        entry.holdAfterValidation();
        log.info("validation result resolved id={} action={} journalEntryId={}", id, action, entry.getId());
    }

    /**
     * 선생님이 막힌 본문을 고친다(#357). 같은 일지의 본문을 바꾸고 검증 대기로 보내 검증을 다시 받게 한다. 아동은 그대로다.
     * 처음 올라온 글은 원본 파일(raw_record)에 남아 있어 따로 보관하지 않고, 고친 사람·시각만 일지에 남긴다.
     */
    @Transactional
    public void revise(Long id, String content, String userId) {
        JournalEntry entry = lockBlockedEntry(id, userId);
        try {
            entry.revise(content, userId);
        } catch (IllegalArgumentException e) {
            // 빈 본문이거나 고치지 않은 본문 — 같은 글을 다시 돌려 판정이 바뀌길 기다리는 우회를 막는다.
            throw new ValidationResultValidationException("invalid revised content: " + e.getMessage());
        }
        log.info("blocked journal entry revised id={} journalEntryId={}", id, entry.getId());
    }

    /**
     * 처리할 막힌 일지를 잠가서 가져온다. 다른 기관 소속이면 "권한 없음"이 아니라 "없음"으로 응답한다
     * (MatchingResultService.resolve와 같은 이유). 다른 선생님이 먼저 처리했으면 거절한다.
     */
    private JournalEntry lockBlockedEntry(Long id, String userId) {
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));

        ValidationResult result = validationResultRepository.findById(id)
                .orElseThrow(() -> new ValidationResultNotFoundException(id));

        OwnedContext ctx = ownedContextOf(journalEntryRepository.findByIdForUpdate(result.getJournalEntryId()),
                institutionId)
                .orElseThrow(() -> new ValidationResultNotFoundException(id));

        if (result.getVerdict() != ValidationVerdict.BLOCK) {
            throw new ValidationResultValidationException("validation result is not blocked: " + result.getVerdict());
        }
        if (!ctx.entry().isAwaitingReinput()) {
            throw new ValidationResultValidationException(
                    "journal entry is not awaiting reinput: " + ctx.entry().getStatus());
        }
        return ctx.entry();
    }

    private record OwnedContext(JournalEntry entry, RawRecord rawRecord) {
    }

    /** journalEntryId가 institutionId 소속 RawRecord로 이어지는지 확인한다 (MatchingResultService와 같은 규칙). */
    private Optional<OwnedContext> resolveOwnedContext(Long journalEntryId, String institutionId) {
        return ownedContextOf(journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId), institutionId);
    }

    private Optional<OwnedContext> ownedContextOf(Optional<JournalEntry> found, String institutionId) {
        return found
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

        List<String> issueTypes = parseIssueTypes(result.getIssueTypes(), result.getId());
        // 매칭 뒤에 아동이 삭제 표시돼도 이름은 보여준다 — 선생님이 어느 기록인지 알아야 한다.
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
        // 유형 → 문장 표와 유형이 없을 때의 문장은 Gate 1 확인 표시와 같이 쓴다 (ValidationReasons)
        return String.join(" / ", ValidationReasons.sentencesOf(issueTypes));
    }

    private static List<String> parseIssueTypes(String raw, Long validationResultId) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> types = new ArrayList<>();
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    types.add(element.asText());
                }
            }
            return types;
        } catch (JsonProcessingException e) {
            // AI가 쓴 값이 깨진 것 — 한 건 때문에 큐 조회 전체가 500 나면 안 된다 (MatchingResultService와 같은 원칙).
            log.warn("failed to parse validation_result.issue_types id={}", validationResultId, e);
            return List.of();
        }
    }
}
