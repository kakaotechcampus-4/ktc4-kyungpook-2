package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.exception.agent.AgentContentTooLongException;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.exception.summary.SummaryTargetException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.InProgressKey;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.SummaryCandidate;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    static final Set<JournalEntryStatus> IN_PROGRESS = Set.of(
            JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING,
            JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING);

    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;
    private final ChildRepository childRepository;
    private final OrganizationRepository organizationRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final SummaryRunRequests runRequests;
    private final ConsentStateReader consentStateReader;
    private final SummaryProperties properties;
    private final Clock clock;

    @Transactional
    public List<ClaimedSummaryGroup> claimReadyGroups(int limit) {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        Map<SummaryGroup, LocalDateTime> requests = runRequests.pendingRequests();
        Set<InProgressKey> busy = Set.copyOf(journalEntryRepository.findInProgressKeys(IN_PROGRESS));
        Map<SummaryGroup, List<SummaryCandidate>> groups = groupCandidates(
                journalEntryRepository.findSummaryCandidates(List.of(JournalEntryStatus.VALIDATED)));
        cancelStaleRequests(requests, groups.keySet(), busy);
        Set<SummaryGroup> consented = properties.autoRun() ? consentedGroups(groups.keySet()) : Set.of();

        List<ClaimedSummaryGroup> claimed = new ArrayList<>();
        for (Map.Entry<SummaryGroup, List<SummaryCandidate>> e : groups.entrySet()) {
            if (claimed.size() >= limit) {
                break;
            }
            SummaryGroup group = e.getKey();
            List<SummaryCandidate> members = e.getValue();
            if (!isReady(group, members, busy, properties.autoRun() ? consented : requests.keySet())) {
                continue;
            }
            List<Long> pending = findLatestPendingEntryIds(group);
            List<Long> ids = Stream.concat(pending.stream(), members.stream().map(SummaryCandidate::journalEntryId))
                    .sorted()
                    .toList();
            journalEntryRepository.findAllById(ids).forEach(JournalEntry::startSummarizing);
            claimed.add(new ClaimedSummaryGroup(group, ids, claimedAt));
        }
        return claimed;
    }

    private void cancelStaleRequests(Map<SummaryGroup, LocalDateTime> requests, Set<SummaryGroup> candidates,
            Set<InProgressKey> busy) {
        requests.keySet().stream()
                .filter(group -> !candidates.contains(group))
                .filter(group -> !busy.contains(
                        new InProgressKey(String.valueOf(group.institutionId()), group.entryDate())))
                .filter(group -> journalEntryRepository.findGroupEntryIds(JournalEntryStatus.SUMMARIZING,
                        group.childId(), group.entryDate(), String.valueOf(group.institutionId())).isEmpty())
                .forEach(group -> runRequests.markHandled(group, requests.get(group)));
    }

    private List<Long> findLatestPendingEntryIds(SummaryGroup group) {
        return summaryResultRepository.findGroupForUpdate(group.childId(), group.entryDate(), group.institutionId())
                .stream().findFirst()
                .filter(SummaryResult::isBeforeApproval)
                .map(latest -> journalEntryRepository.findIdsBySummaryIdAndStatus(
                        latest.getId(), JournalEntryStatus.GATE1_PENDING))
                .orElse(List.of());
    }

    private Map<SummaryGroup, List<SummaryCandidate>> groupCandidates(List<SummaryCandidate> candidates) {
        Map<SummaryGroup, List<SummaryCandidate>> groups = new LinkedHashMap<>();
        for (SummaryCandidate c : candidates) {
            Long institutionId = parseInstitutionId(c);
            if (institutionId == null) {
                continue;
            }
            groups.computeIfAbsent(new SummaryGroup(c.childId(), c.entryDate(), institutionId), k -> new ArrayList<>())
                    .add(c);
        }
        return groups;
    }

    private boolean isReady(SummaryGroup group, List<SummaryCandidate> members, Set<InProgressKey> busy,
            Set<SummaryGroup> allowed) {
        String institutionId = members.get(0).institutionId();
        if (busy.contains(new InProgressKey(institutionId, group.entryDate()))) {
            return false;
        }
        return allowed.contains(group);
    }

    private Set<SummaryGroup> consentedGroups(Set<SummaryGroup> groups) {
        Map<Long, List<SummaryGroup>> byInstitution = groups.stream()
                .collect(Collectors.groupingBy(SummaryGroup::institutionId));
        Set<SummaryGroup> consented = new HashSet<>();
        byInstitution.forEach((institutionId, members) -> {
            Set<Long> granted = consentStateReader.grantedChildIds(
                    members.stream().map(SummaryGroup::childId).collect(Collectors.toSet()), institutionId);
            members.stream().filter(g -> granted.contains(g.childId())).forEach(consented::add);
        });
        return consented;
    }

    @Transactional(readOnly = true)
    public SummaryAgentRequest prepareRequest(ClaimedSummaryGroup claimed) {
        SummaryGroup group = claimed.group();
        List<JournalEntry> entries = journalEntryRepository.findAllById(claimed.journalEntryIds()).stream()
                .filter(entry -> !entry.isDeleted())
                .sorted(Comparator.comparing(JournalEntry::getId))
                .toList();
        if (entries.isEmpty()) {
            throw new SummaryTargetException("no journal entry left in summary group: " + group);
        }
        for (JournalEntry entry : entries) {
            if (!entry.isSendableToAgent()) {
                throw new AgentContentTooLongException(entry.getId(), entry.getContent().length());
            }
        }
        String childName = childRepository.findById(group.childId()).map(Child::getName).orElse(null);
        String institutionName = organizationRepository.findById(group.institutionId())
                .map(Organization::getName).orElse(null);
        String entryDate = group.entryDate().toString();

        List<SummaryAgentRequest.Source> sources = entries.stream()
                .map(entry -> new SummaryAgentRequest.Source(entry.getId(), entry.getContent(), entryDate))
                .toList();
        return new SummaryAgentRequest(group.childId(), childName, entryDate, group.institutionId(),
                institutionName, sources, otherChildNames(group.childId(), entries));
    }

    private List<String> otherChildNames(Long childId, List<JournalEntry> entries) {
        Map<Long, MatchingResult> latest = new HashMap<>();
        for (MatchingResult result : matchingResultRepository.findByJournalEntryIdIn(
                entries.stream().map(JournalEntry::getId).toList())) {
            latest.merge(result.getJournalEntryId(), result, (a, b) -> a.getId() > b.getId() ? a : b);
        }
        Set<Long> others = new TreeSet<>();
        latest.values().forEach(result -> others.addAll(JsonColumns.readLongsAllowingText(
                result.getMentionedChildIds(), "matching_result.mentioned_child_ids", result.getId())));
        others.remove(childId);
        if (others.isEmpty()) {
            return List.of();
        }
        return childRepository.findAllById(others).stream()
                .sorted(Comparator.comparing(Child::getId))
                .map(Child::getName)
                .distinct()
                .toList();
    }

    @Transactional
    public void release(List<ClaimedSummaryGroup> groups) {
        List<Long> ids = groups.stream().flatMap(g -> g.journalEntryIds().stream()).toList();
        journalEntryRepository.findAllById(ids).stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.SUMMARIZING)
                .forEach(JournalEntry::releaseSummarizing);
    }

    @Transactional
    public int releaseStuck() {
        List<JournalEntry> stuck = journalEntryRepository.findByStatusAndDeletedAtIsNull(JournalEntryStatus.SUMMARIZING);
        stuck.forEach(JournalEntry::releaseSummarizing);
        return stuck.size();
    }

    private Long parseInstitutionId(SummaryCandidate c) {
        try {
            return Long.valueOf(c.institutionId());
        } catch (NumberFormatException e) {
            log.warn("summary candidate skipped: institution id is not numeric journalEntryId={} institutionId={}",
                    c.journalEntryId(), c.institutionId());
            return null;
        }
    }
}
