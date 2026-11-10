package com.itda.backend.service.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingResultService {

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
        return matchingResultRepository.findByStatusNot(MatchingStatus.AUTO).stream()
                .flatMap(mr -> resolveOwnedContext(mr.getJournalEntryId(), institutionId)
                        .filter(ctx -> ctx.entry().isAwaitingReview())
                        .map(ctx -> buildResponse(mr, ctx, organizationId))
                        .stream())
                .toList();
    }

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

        OwnedContext ctx = resolveOwnedContext(matchingResult.getJournalEntryId(), String.valueOf(organizationId))
                .orElseThrow(() -> new MatchingResultNotFoundException(id));
        if (!ctx.entry().isAwaitingReview()) {
            throw new MatchingResultValidationException("journal entry is not awaiting review: " + ctx.entry().getStatus());
        }
        if (matchingResult.getStatus() == MatchingStatus.AUTO) {
            throw new MatchingResultValidationException("matching result already resolved: " + id);
        }

        switch (action == null ? "" : action) {
            case "assign" -> {
                if (childId == null) {
                    throw new MatchingResultValidationException("childId is required for assign");
                }
                childRepository.findByIdForUpdate(childId)
                        .filter(child -> !child.isDeleted())
                        .orElseThrow(() -> new MatchingResultValidationException("child not available: " + childId));
                if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
                    throw new MatchingResultValidationException("child not available: " + childId);
                }
                if (!sharingConsentRepository.existsByChildIdAndOrganizationIdAndStatus(
                        childId, organizationId, SharingConsentStatus.ACTIVE)) {
                    throw new MatchingResultValidationException("child consent not completed: " + childId);
                }
                agentQueueGuard.requireRoom(organizationId, 1);
                matchingResult.resolveAsAssigned(childId, reviewerId);
                ctx.entry().confirmMatchByReviewer(childId);
            }
            case "not_ours" -> {
                matchingResult.resolveAsNotOurs(reviewerId);
                ctx.entry().exclude();
            }
            default -> throw new MatchingResultValidationException("unsupported action for matching result: " + id);
        }

        MatchingResult saved = matchingResultRepository.save(matchingResult);
        return buildResponse(saved, ctx, organizationId);
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
                JsonColumns.readTree(mr.getEvidence(), "matching_result.evidence", mr.getId()));
    }

    private List<CandidateResponse> buildCandidates(String candidatesJson, Long matchingResultId, Long organizationId) {
        JsonNode node = JsonColumns.readTree(candidatesJson, "matching_result.candidates", matchingResultId);
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }

        List<Long> childIds = new ArrayList<>();
        for (JsonNode c : node) {
            JsonNode childIdNode = c.get("child_id");
            if (childIdNode != null && childIdNode.isIntegralNumber()) {
                childIds.add(childIdNode.asLong());
            }
        }
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
}
