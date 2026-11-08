package com.itda.backend.service.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.dto.response.matching.CandidateResponse;
import com.itda.backend.dto.response.matching.MatchingQueueItemResponse;
import com.itda.backend.dto.response.record.RecordResponse;
import com.itda.backend.exception.matching.MatchingResultNotFoundException;
import com.itda.backend.exception.matching.MatchingResultValidationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.service.record.RawRecordService;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.agent.AgentQueueGuard;

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
    private final SharingConsentRepository sharingConsentRepository;
    private final UserService userService;
    private final AgentQueueGuard agentQueueGuard;

    @Transactional(readOnly = true)
    public List<MatchingQueueItemResponse> getQueue(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        String institutionId = String.valueOf(organizationId);
        // ponytail: 건별로 JournalEntry/RawRecord 조회 + candidates별 Child 조회 — N+1이지만
        // 지금 큐 규모에선 문제없다. 커지면 join 쿼리로 바꿀 것.
        return matchingResultRepository.findByStatusNot(MatchingStatus.AUTO).stream()
                .flatMap(mr -> resolveOwnedContext(mr.getJournalEntryId(), institutionId)
                        // 일지가 이미 처리됐으면(옛 결과 행 등) resolve 가 거절하므로 큐에서도 뺀다.
                        .filter(ctx -> ctx.entry().isAwaitingReview())
                        .map(ctx -> buildResponse(mr, ctx, organizationId))
                        .stream())
                .toList();
    }

    /**
     * 큐 항목 하나 (O-28, 11주차 기술멘토링 "필요한 정보만, 1건씩"). 목록과 같은 모양이다.
     * 우리 기관의 처리 대기 항목이 아니면 다른 기관 것이든 이미 처리된 것이든 모두 "없음"이다 —
     * 나누면 그 id 가 어딘가에 있다는 사실이 새어 나간다.
     */
    @Transactional(readOnly = true)
    public MatchingQueueItemResponse getItem(Long id, String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        MatchingResult matchingResult = matchingResultRepository.findById(id)
                .filter(mr -> mr.getStatus() != MatchingStatus.AUTO)
                .orElseThrow(() -> new MatchingResultNotFoundException(id));
        OwnedContext ctx = resolveOwnedContext(matchingResult.getJournalEntryId(), String.valueOf(organizationId))
                .filter(c -> c.entry().isAwaitingReview())
                .orElseThrow(() -> new MatchingResultNotFoundException(id));
        return buildResponse(matchingResult, ctx, organizationId);
    }

    @Transactional
    public MatchingQueueItemResponse resolve(Long id, String action, Long childId, String reviewerId, String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);

        MatchingResult matchingResult = matchingResultRepository.findById(id)
                .orElseThrow(() -> new MatchingResultNotFoundException(id));

        // 다른 기관 소속이면 "권한 없음"이 아니라 "없음"으로 응답한다 (RawRecordService.getById와 같은 이유).
        OwnedContext ctx = resolveOwnedContext(matchingResult.getJournalEntryId(), String.valueOf(organizationId))
                .orElseThrow(() -> new MatchingResultNotFoundException(id));
        // 이미 처리된 일지(다른 선생님이 먼저 처리 등)는 다시 처리하지 않는다.
        if (!ctx.entry().isAwaitingReview()) {
            throw new MatchingResultValidationException("journal entry is not awaiting review: " + ctx.entry().getStatus());
        }
        // 일지 FAILED는 #180 전의 검증 호출 실패로도 생겼다(배포 DB에 남아 있다) — 매칭 결과가 이미
        // AUTO(AI 확정 또는 사람이 처리함)면 매칭은 끝난 것이라 일지 상태만 보고 다시 처리하면 확정된 아이가
        // 바뀌거나 제외돼 버린다.
        if (matchingResult.getStatus() == MatchingStatus.AUTO) {
            throw new MatchingResultValidationException("matching result already resolved: " + id);
        }

        switch (action == null ? "" : action) {
            case "assign" -> {
                if (childId == null) {
                    throw new MatchingResultValidationException("childId is required for assign");
                }
                // 아동 행을 잠근 뒤 동의를 본다. 동의 부여·철회도 이 행을 먼저 잠그므로, 철회가 커밋된 뒤에는
                // 새 확정이 성공하지 않는다. 잠그지 않으면 "동의 확인 → 철회 커밋 → 확정" 순서로 끼어들 수 있다.
                // 없는 아이와 다른 기관 아이는 같은 문구로 거절한다. 메시지가 응답에 그대로 나가므로, 나누면
                // 아이 번호를 바꿔 가며 다른 기관에 어떤 아이가 있는지 알아낼 수 있다.
                childRepository.findByIdForUpdate(childId)
                        .filter(child -> !child.isDeleted())
                        .orElseThrow(() -> new MatchingResultValidationException("child not available: " + childId));
                if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
                    throw new MatchingResultValidationException("child not available: " + childId);
                }
                // AI 명단(ChildRepository.findConsentedByOrganizationId)과 같은 기준 — 이 기관에 동의하지 않은
                // 아동으로는 확정하지 않는다. 다른 기관에만 동의해 child.status 가 ACTIVE 인 아이도 막힌다.
                if (!sharingConsentRepository.existsByChildIdAndOrganizationIdAndStatus(
                        childId, organizationId, SharingConsentStatus.ACTIVE)) {
                    throw new MatchingResultValidationException("child consent not completed: " + childId);
                }
                // 검증 대기로 다시 들어간다 — AI 처리 줄에 자리가 있는지 본다 (#424). 아동 행을 잠근 뒤에 기관 행을 잠근다.
                agentQueueGuard.requireRoom(organizationId, 1);
                matchingResult.resolveAsAssigned(childId, reviewerId);
                ctx.entry().confirmMatchByReviewer(childId);
            }
            case "not_ours" -> {
                matchingResult.resolveAsNotOurs(reviewerId);
                ctx.entry().exclude();
            }
            default -> throw new MatchingResultValidationException("unsupported action: " + action);
        }

        MatchingResult saved = matchingResultRepository.save(matchingResult);
        return buildResponse(saved, ctx, organizationId);
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

    private MatchingQueueItemResponse buildResponse(MatchingResult mr, OwnedContext ctx, Long organizationId) {
        RecordResponse record = RecordResponse.of(
                String.valueOf(ctx.rawRecord().getId()),
                ctx.rawRecord().getOriginalFilename(),
                ctx.entry().getContent(),
                ctx.entry().getEntryDate() == null ? null : ctx.entry().getEntryDate().toString());

        return new MatchingQueueItemResponse(
                String.valueOf(mr.getId()),
                mr.getJournalEntryId(),
                mr.getMatchedChildId() == null ? null : String.valueOf(mr.getMatchedChildId()),
                mr.getStatus(),
                mr.getConfidence(),
                mr.getMultiReason(),
                mr.getHintMismatch(),
                ctx.rawRecord().getHintName(),
                record,
                buildCandidates(mr.getCandidates(), mr.getId(), organizationId),
                parseJson(mr.getEvidence(), mr.getId()));
    }

    private List<CandidateResponse> buildCandidates(String candidatesJson, Long matchingResultId, Long organizationId) {
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
        // 우리 기관 명부(연결 해제·삭제 제외)에 있는 아이만 후보로 보여준다. child_id 는 AI 가 쓴 값이라
        // 명부 밖 번호가 오면 다른 기관 아이의 이름·생년월일이 나간다 — 규칙으로 막는다(CLAUDE.md:
        // 접근 판단을 LLM 동작에 맡기지 않는다). 보여줘도 assign 이 거부돼 화면과 처리 가능 여부가 어긋나기도 한다.
        Set<Long> roster = childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(childIds).stream()
                .filter(link -> link.getOrganizationId().equals(organizationId))
                .map(ChildOrganization::getChildId)
                .collect(Collectors.toSet());
        Map<Long, Child> children = childRepository.findAllById(roster).stream()
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
            Child child = children.get(childId);
            if (child == null) {
                log.warn("skipping candidate outside our roster matchingResultId={} childId={}", matchingResultId, childId);
                continue;
            }
            JsonNode confidenceNode = c.get("confidence");
            Double confidence = confidenceNode != null && confidenceNode.isNumber() ? confidenceNode.asDouble() : null;
            result.add(new CandidateResponse(
                    String.valueOf(childId), child.getName(), child.getBirthdate().toString(), confidence));
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
