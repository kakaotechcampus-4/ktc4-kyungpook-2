package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.dto.response.summary.FailedSummaryGroupResponse;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.GroupKey;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.agent.AgentQueueGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryFailureService {

    static final String REASON_NO_GROUNDED_CLAIMS = "NO_GROUNDED_CLAIMS";

    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildRepository childRepository;
    private final ConsentStateReader consentStateReader;
    private final SummaryRunRequests runRequests;
    private final SummaryProperties properties;
    private final Clock clock;
    private final AgentQueueGuard agentQueueGuard;

    @Transactional(readOnly = true)
    public List<FailedSummaryGroupResponse> getFailedGroups(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<JournalEntry> entries = journalEntryRepository.findByInstitutionAndStatus(
                String.valueOf(organizationId), JournalEntryStatus.SUMMARY_FAILED);
        if (entries.isEmpty()) {
            return List.of();
        }
        Set<Long> granted = consentStateReader.grantedChildIds(
                entries.stream().map(JournalEntry::getChildId).collect(Collectors.toSet()), organizationId);
        Map<GroupKey, List<JournalEntry>> groups = entries.stream()
                .filter(e -> granted.contains(e.getChildId()))
                .collect(Collectors.groupingBy(e -> new GroupKey(e.getChildId(), e.getEntryDate()),
                        LinkedHashMap::new, Collectors.toList()));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, String> childNames = childRepository.findAllById(granted).stream()
                .collect(Collectors.toMap(Child::getId, Child::getName));
        return groups.entrySet().stream()
                .map(group -> new FailedSummaryGroupResponse(
                        group.getKey().childId(),
                        childNames.get(group.getKey().childId()),
                        group.getKey().entryDate(),
                        group.getValue().stream()
                                .map(e -> new FailedSummaryGroupResponse.Entry(e.getId(), e.getContent()))
                                .toList(),
                        REASON_NO_GROUNDED_CLAIMS))
                .toList();
    }

    @Transactional
    public void retry(String userId, Long childId, LocalDate entryDate) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<JournalEntry> failed = lockFailedGroup(organizationId, childId, entryDate);
        agentQueueGuard.requireRoom(organizationId, failed.size());
        failed.forEach(JournalEntry::retrySummary);
        if (!properties.autoRun()) {
            runRequests.request(new SummaryGroup(childId, entryDate, organizationId), LocalDateTime.now(clock));
        }
        log.info("failed summary group sent back to summary wait childId={} entryDate={} institutionId={} "
                + "journalEntryIds={}", childId, entryDate, organizationId, idsOf(failed));
    }

    @Transactional
    public void close(String userId, Long childId, LocalDate entryDate) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<JournalEntry> failed = lockFailedGroup(organizationId, childId, entryDate);
        failed.forEach(JournalEntry::closeFailedSummary);
        log.info("failed summary group closed without summary childId={} entryDate={} institutionId={} userId={} "
                + "journalEntryIds={}", childId, entryDate, organizationId, userId, idsOf(failed));
    }

    private List<JournalEntry> lockFailedGroup(Long organizationId, Long childId, LocalDate entryDate) {
        childRepository.findByIdForUpdate(childId);
        if (consentStateReader.stateOf(childId, organizationId) != ConsentState.GRANTED) {
            throw new SummaryChildNotFoundException(childId);
        }
        List<Long> ids = journalEntryRepository.findGroupEntryIds(JournalEntryStatus.SUMMARY_FAILED, childId,
                entryDate, String.valueOf(organizationId));
        if (ids.isEmpty()) {
            throw new SummaryException(SummaryErrorCode.FAILED_NOT_FOUND,
                    "childId=" + childId + " entryDate=" + entryDate);
        }
        return journalEntryRepository.findAllById(ids);
    }

    private static List<Long> idsOf(List<JournalEntry> entries) {
        return entries.stream().map(JournalEntry::getId).sorted().toList();
    }
}
