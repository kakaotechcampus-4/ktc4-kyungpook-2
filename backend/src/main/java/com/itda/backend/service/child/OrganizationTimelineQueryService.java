package com.itda.backend.service.child;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.child.OrganizationTimelineEntryResponse;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationTimelineQueryService {

    private final OrganizationChildGuard organizationChildGuard;
    private final ChildContextRepository childContextRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final ApprovalRepository approvalRepository;

    @Transactional(readOnly = true)
    public List<OrganizationTimelineEntryResponse> timeline(String userId, Long childId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        List<ChildContext> contexts = childContextRepository.findByChildIdOrderByIdDesc(childId);
        if (contexts.isEmpty()) {
            return List.of();
        }
        Map<Long, SummaryResult> ours = summaryResultRepository
                .findAllById(contexts.stream().map(ChildContext::getSummaryResultId).distinct().toList())
                .stream()
                .filter(summary -> organizationId.equals(summary.getInstitutionId()))
                .collect(Collectors.toMap(SummaryResult::getId, Function.identity()));
        if (ours.isEmpty()) {
            return List.of();
        }
        Set<Long> edited = approvalRepository
                .findByTargetTypeAndDecisionAndTargetIdIn(
                        ApprovalTargetType.SUMMARY, ApprovalDecision.CORRECTED, ours.keySet())
                .stream()
                .map(Approval::getTargetId)
                .collect(Collectors.toSet());

        return contexts.stream()
                .map(context -> {
                    SummaryResult summary = ours.get(context.getSummaryResultId());
                    return summary == null ? null : new OrganizationTimelineEntryResponse(
                            String.valueOf(context.getId()),
                            summary.getEntryDate(),
                            context.getContent(),
                            edited.contains(summary.getId()),
                            sourceCountOf(summary));
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OrganizationTimelineEntryResponse::date).reversed())
                .toList();
    }

    private static int sourceCountOf(SummaryResult summary) {
        return JsonColumns.readLongs(summary.getCoveredEntryIds(), "summary_result.covered_entry_ids", summary.getId())
                .size();
    }
}
