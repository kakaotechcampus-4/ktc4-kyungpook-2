package com.itda.backend.service;

import java.util.ArrayList;
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
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.MatchingStatus;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.dto.CandidateResponse;
import com.itda.backend.dto.MatchingQueueItemResponse;
import com.itda.backend.dto.RecordResponse;
import com.itda.backend.exception.MatchingResultNotFoundException;
import com.itda.backend.exception.MatchingResultValidationException;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;
import com.itda.backend.repository.RawRecordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// backend/AGENTS.md: 조회는 @Transactional(readOnly = true), 변경은 @Transactional로 범위를 명시한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingResultService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MatchingResultRepository matchingResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final RawRecordRepository rawRecordRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<MatchingQueueItemResponse> getQueue(String userId) {
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));
        // ponytail: 건별로 JournalEntry/RawRecord 조회 + candidates별 Child 조회 — N+1이지만
        // 지금 큐 규모에선 문제없다. 커지면 join 쿼리로 바꿀 것.
        return matchingResultRepository.findByStatusNot(MatchingStatus.AUTO).stream()
                .flatMap(mr -> resolveOwnedContext(mr.getJournalEntryId(), institutionId)
                        .map(ctx -> buildResponse(mr, ctx))
                        .stream())
                .toList();
    }

    @Transactional
    public MatchingQueueItemResponse resolve(Long id, String action, Long childId, String reviewerId, String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);

        MatchingResult matchingResult = matchingResultRepository.findById(id)
                .orElseThrow(() -> new MatchingResultNotFoundException(id));

        // 다른 기관 소속이면 "권한 없음"이 아니라 "없음"으로 응답한다 (RawRecordService.getById와 같은 이유).
        OwnedContext ctx = resolveOwnedContext(matchingResult.getJournalEntryId(), String.valueOf(organizationId))
                .orElseThrow(() -> new MatchingResultNotFoundException(id));

        switch (action == null ? "" : action) {
            case "assign" -> {
                if (childId == null) {
                    throw new MatchingResultValidationException("childId is required for assign");
                }
                childRepository.findByIdAndDeletedAtIsNull(childId)
                        .orElseThrow(() -> new MatchingResultValidationException("child not found: " + childId));
                if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
                    throw new MatchingResultValidationException("child not in this institution: " + childId);
                }
                matchingResult.resolveAsAssigned(childId, reviewerId);
            }
            case "not_ours" -> matchingResult.resolveAsNotOurs(reviewerId);
            default -> throw new MatchingResultValidationException("unsupported action: " + action);
        }

        MatchingResult saved = matchingResultRepository.save(matchingResult);
        return buildResponse(saved, ctx);
    }

    private record OwnedContext(JournalEntry entry, RawRecord rawRecord) {
    }

    /**
     * journalEntryId가 institutionId 소속 RawRecord로 이어지는지 확인한다. 못 이어지면 비운다(=제외/거부).
     *
     * ponytail: rawRecordId가 NULL인 JournalEntry(플랫폼 직접 입력 경로, JournalEntry.java 참고)는
     * 지금 구조상 소속 기관을 못 밝혀내서 여기서 항상 제외된다. 직접 입력 기능을 실제로 만들 때
     * (matchingStatus=SKIPPED로 생성 예정) 이 경로가 확인 필요 큐에 뜨면 안 되니 지금은 괜찮지만,
     * 다른 목적으로 조회해야 할 상황이 생기면 이 필터가 그걸 조용히 막고 있다는 걸 기억할 것.
     */
    private Optional<OwnedContext> resolveOwnedContext(Long journalEntryId, String institutionId) {
        return journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId)
                .filter(entry -> entry.getRawRecordId() != null)
                .flatMap(entry -> rawRecordRepository.findByIdAndDeletedAtIsNull(entry.getRawRecordId())
                        .filter(record -> record.getInstitutionId().equals(institutionId))
                        .map(record -> new OwnedContext(entry, record)));
    }

    private MatchingQueueItemResponse buildResponse(MatchingResult mr, OwnedContext ctx) {
        RecordResponse record = new RecordResponse(
                String.valueOf(ctx.rawRecord().getId()),
                ctx.rawRecord().getOriginalFilename(),
                preview(ctx.entry().getContent()),
                ctx.entry().getEntryDate() == null ? null : ctx.entry().getEntryDate().toString());

        return new MatchingQueueItemResponse(
                String.valueOf(mr.getId()),
                mr.getJournalEntryId(),
                mr.getMatchedChildId() == null ? null : String.valueOf(mr.getMatchedChildId()),
                mr.getStatus(),
                mr.getConfidence(),
                mr.getMultiReason(),
                mr.getHintMismatch(),
                record,
                buildCandidates(mr.getCandidates(), mr.getId()),
                parseJson(mr.getEvidence(), mr.getId()));
    }

    private static final int PREVIEW_LENGTH = 60;

    private String preview(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH) + "…";
    }

    private List<CandidateResponse> buildCandidates(String candidatesJson, Long matchingResultId) {
        JsonNode node = parseJson(candidatesJson, matchingResultId);
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }

        // AI가 쓴 원문이라 "child_id"/"confidence" 중 하나라도 빠지거나 배열 원소가 객체가
        // 아닐 수 있다 — 그런 항목은 건너뛴다(로그만 남김). 한 건 깨졌다고 큐 조회 전체가
        // 500 나면 안 된다는 원칙은 parseJson()과 같다.
        List<Long> childIds = new ArrayList<>();
        for (JsonNode c : node) {
            JsonNode childIdNode = c.get("child_id");
            if (childIdNode != null && childIdNode.isIntegralNumber()) {
                childIds.add(childIdNode.asLong());
            }
        }
        // 소속 기관이 삭제 처리한 아이는 후보로도 안 보여준다 — 보여줘도 assign이 거부돼서
        // 화면과 실제 처리 가능 여부가 어긋난다.
        Map<Long, Child> children = childRepository.findAllById(childIds).stream()
                .filter(child -> !child.isDeleted())
                .collect(Collectors.toMap(Child::getId, child -> child));

        List<CandidateResponse> result = new ArrayList<>();
        for (JsonNode c : node) {
            JsonNode childIdNode = c.get("child_id");
            if (childIdNode == null || !childIdNode.isIntegralNumber()) {
                log.warn("skipping malformed candidate (missing/invalid child_id) matchingResultId={}", matchingResultId);
                continue;
            }
            long childId = childIdNode.asLong();
            JsonNode confidenceNode = c.get("confidence");
            Double confidence = confidenceNode != null && confidenceNode.isNumber() ? confidenceNode.asDouble() : null;
            Child child = children.get(childId);
            result.add(new CandidateResponse(
                    String.valueOf(childId),
                    child == null ? null : child.getName(),
                    child == null ? null : child.getBirthdate().toString(),
                    confidence));
        }
        return result;
    }

    private static JsonNode parseJson(String raw, Long matchingResultId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            // AI가 쓴 값이 깨진 것 — 조용히 숨기지 않고 로그로 남긴다. 큐 조회 자체는 계속되게
            // null로만 내려준다(한 건 깨졌다고 전체 큐 조회가 500 나면 더 큰 문제가 된다).
            log.warn("failed to parse matching_result json id={}", matchingResultId, e);
            return null;
        }
    }
}
