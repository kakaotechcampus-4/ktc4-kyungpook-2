package com.itda.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.ValidationResult;
import com.itda.backend.domain.ValidationVerdict;
import com.itda.backend.dto.RecordResponse;
import com.itda.backend.dto.ValidationQueueItemResponse;
import com.itda.backend.exception.ValidationResultNotFoundException;
import com.itda.backend.exception.ValidationResultValidationException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.RawRecordRepository;
import com.itda.backend.repository.ValidationResultRepository;

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

    /**
     * AI 가 보낸 이슈 유형(AI/validation/config.py ISSUE_LEVEL)을 선생님이 읽을 문장으로 바꾼다.
     *
     * <p>화면에 그대로 뜨는 값이라 "무엇이 문제인지"가 바로 읽혀야 한다. AI 가 유형을 새로 만들면
     * 여기 없는 값이 올 수 있어서, 없으면 원본 코드를 그대로 보여준다 — 빈칸보다 낫다.
     */
    private static final Map<String, String> VIOLATION_REASONS = Map.of(
            "진단명", "진단명이나 장애 유형이 단정적으로 적혀 있습니다",
            "개인정보표현", "전화번호·주민번호 같은 개인정보가 그대로 적혀 있습니다",
            "다수아동언급", "다른 아이의 이름이 함께 적혀 있습니다",
            "확정적표현", "관찰이 아니라 단정하는 표현이 있습니다",
            "추측성표현", "관찰되지 않은 내용을 추측한 표현이 있습니다",
            "감정적표현", "감정이나 평가가 섞인 표현이 있습니다",
            "위험행동표현", "위험 행동이 그대로 묘사돼 있습니다");

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
     * 선생님이 막힌 기록을 처리한다. 둘 다 이 일지의 파이프라인을 여기서 끝낸다.
     *
     * <p>{@code reupload} 는 고친 내용을 새 파일로 올리겠다는 뜻이다 — 원본은 고치지도 지우지도
     * 않는다(append-only, CLAUDE.md Raw Data 규칙). 그래서 이 일지를 되살리지 않고 종료시킨다.
     */
    @Transactional
    public void resolve(Long id, String action, String userId) {
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));

        ValidationResult result = validationResultRepository.findById(id)
                .orElseThrow(() -> new ValidationResultNotFoundException(id));

        // 다른 기관 소속이면 "권한 없음"이 아니라 "없음"으로 응답한다 (MatchingResultService.resolve와 같은 이유).
        OwnedContext ctx = resolveOwnedContext(result.getJournalEntryId(), institutionId)
                .orElseThrow(() -> new ValidationResultNotFoundException(id));

        if (result.getVerdict() != ValidationVerdict.BLOCK) {
            throw new ValidationResultValidationException("validation result is not blocked: " + result.getVerdict());
        }
        if (!ctx.entry().isAwaitingReinput()) {
            throw new ValidationResultValidationException(
                    "journal entry is not awaiting reinput: " + ctx.entry().getStatus());
        }

        switch (action == null ? "" : action) {
            case "reupload" -> ctx.entry().requestReupload();
            case "hold" -> ctx.entry().holdAfterValidation();
            default -> throw new ValidationResultValidationException("unsupported action: " + action);
        }
        log.info("validation result resolved id={} action={} journalEntryId={}",
                id, action, result.getJournalEntryId());
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
        if (issueTypes.isEmpty()) {
            // AI가 BLOCK을 주면서 유형을 안 보낸 경우 — 화면이 빈칸이 되면 선생님이 할 수 있는 게 없다.
            return "검증에서 문제가 발견됐습니다. 원본을 확인해주세요";
        }
        return issueTypes.stream()
                .map(type -> VIOLATION_REASONS.getOrDefault(type, type))
                .distinct()
                .reduce((a, b) -> a + " / " + b)
                .orElseThrow();
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
