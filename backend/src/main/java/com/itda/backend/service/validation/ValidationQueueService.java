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
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;

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
                        // 이미 처리된 일지(다른 창에서 먼저 처리 등)는 큐에서 뺀다 — 눌러도 resolve가 거절한다.
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
        // 잘못된 action 은 일지를 잠그기 전에 거절한다 — 그동안 같은 일지의 다른 처리가 잠금을 기다리지 않게.
        if (!"hold".equals(action)) {
            throw new ValidationResultValidationException("unsupported action: " + action);
        }
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));
        ValidationResult result = findResult(id);
        requireOwnedBlock(result, institutionId);

        JournalEntry entry = lockAwaitingEntry(result);
        entry.holdAfterValidation();
        log.info("validation result resolved id={} action={} journalEntryId={}", id, action, entry.getId());
    }

    /**
     * 선생님이 막힌 본문을 고친다(#357). 같은 일지의 본문을 바꾸고 검증 대기로 보내 검증을 다시 받게 한다. 아동은 그대로다.
     * 처음 올라온 글은 원본 파일(raw_record)에 남아 있어 따로 보관하지 않고, 고친 사람·시각만 일지에 남긴다.
     *
     * <p>고치면 본문과 아이 이름이 다시 AI 검증으로 가므로, 매칭 확정(MatchingResultService.resolve)과 같이 아이가 아직
     * 우리 명부에 있고 우리 기관 동의가 켜져 있는지 본다. 아동 행을 일지보다 먼저 잠근다 — 동의 철회·명부 제외도 이 행을
     * 먼저 잠그므로 철회가 커밋된 뒤에는 고치기가 성공하지 않는다 (잠금 순서 child → journal_entry, DB 스키마 §3.3).
     * 잠글 아동은 판정의 아동이다. 막힌 일지는 아동이 바뀌지 않으므로 일지를 잠근 뒤 같은지 확인한다.
     */
    @Transactional
    public void revise(Long id, String content, String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        ValidationResult result = findResult(id);
        requireOwnedBlock(result, String.valueOf(organizationId));

        Long childId = result.getChildId();
        if (childId == null) {
            // 검증은 아동이 정해진 일지만 한다. 비어 있으면 데이터가 망가진 것이라 입력 오류와 구분한다.
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
        try {
            entry.revise(content, userId);
        } catch (IllegalArgumentException e) {
            // 빈 본문 — 요청 검증(@NotBlank)을 거치지 않고 들어온 경우.
            throw new ValidationResultValidationException("invalid revised content: " + e.getMessage());
        }
        log.info("blocked journal entry revised id={} journalEntryId={}", id, entry.getId());
    }

    private ValidationResult findResult(Long id) {
        return validationResultRepository.findById(id)
                .orElseThrow(() -> new ValidationResultNotFoundException(id));
    }

    /**
     * 행을 잠그기 전에, 우리 기관 기록의 BLOCK 판정인지 본다. 다른 기관 소속이면 "권한 없음"이 아니라 "없음"으로 응답한다
     * (MatchingResultService.resolve와 같은 이유). 남의 행은 잠그지 않는다 — 잠그면 그 기관의 처리가 기다리고, 기다린
     * 시간으로 id 가 있는지도 드러난다 (SummaryGate1Service.lockOwnedSummary 와 같은 이유). 판정은 바뀌지 않으므로
     * 잠그지 않고 봐도 된다.
     */
    private void requireOwnedBlock(ValidationResult result, String institutionId) {
        if (!journalEntryRepository.existsOwnedBy(result.getJournalEntryId(), institutionId)) {
            throw new ValidationResultNotFoundException(result.getId());
        }
        if (result.getVerdict() != ValidationVerdict.BLOCK) {
            throw new ValidationResultValidationException("validation result is not blocked: " + result.getVerdict());
        }
    }

    /**
     * 처리할 막힌 일지를 잠가서 가져온다. 이미 처리됐으면 거절한다.
     *
     * <p>화면이 보던 판정이 그 일지의 최신 판정이어야 한다. 이미 고쳐 다시 검사했는데 또 막혀 새 판정이 생겼으면 일지는
     * 다시 "막힘"이라 상태만으로는 구분되지 않고, 옛 카드로 저장하면 먼저 고친 본문을 덮어쓴다.
     */
    private JournalEntry lockAwaitingEntry(ValidationResult result) {
        Long id = result.getId();
        // 소유 확인 뒤 잠금을 기다리는 사이 삭제됐으면 없는 것이다.
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

    /**
     * 아이가 아직 우리 명부에 있고 우리 기관 동의가 켜져 있는지. 없는 아이·명부에서 뺀 아이·동의 철회를 같은 이유로
     * 거절한다 — 선생님이 할 수 있는 일은 보류뿐이라 나눌 필요가 없다. 동의 상태는 ConsentStateReader 로만 계산한다.
     *
     * @param child 이미 잠근 {@code childId} 아동 행
     */
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

    /** journalEntryId가 institutionId 소속 RawRecord로 이어지는지 확인한다 (MatchingResultService와 같은 규칙). */
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
