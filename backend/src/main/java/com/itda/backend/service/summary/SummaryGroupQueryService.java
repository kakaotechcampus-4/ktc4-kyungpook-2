package com.itda.backend.service.summary;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.Counts;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.MatchingCounts;
import com.itda.backend.dto.response.summary.SummaryGroupBoardResponse.SummaryGroupCard;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.DateCount;
import com.itda.backend.repository.record.GroupKey;
import com.itda.backend.repository.record.GroupStatusCount;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SummaryGroupQueryService {

    private static final String NEEDS_FIX = "needs_fix";
    private static final String READY = "ready";
    private static final String SUMMARIZING = "summarizing";
    private static final String VALIDATING = "validating";
    private static final String ADMIN_CHECK = "admin_check";

    private static final Set<JournalEntryStatus> CARD = Set.of(JournalEntryStatus.MATCHED,
            JournalEntryStatus.VALIDATING, JournalEntryStatus.VALIDATION_BLOCKED, JournalEntryStatus.VALIDATED,
            JournalEntryStatus.SUMMARIZING, JournalEntryStatus.SYSTEM_ERROR);

    private static final Set<JournalEntryStatus> MATCHING_IN_PROGRESS =
            Set.of(JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING);

    private static final Set<JournalEntryStatus> AWAITING_MATCH_REVIEW =
            Set.of(JournalEntryStatus.MATCH_REVIEW, JournalEntryStatus.FAILED);

    private static final Comparator<GroupKey> OLDEST_FIRST =
            Comparator.comparing(GroupKey::entryDate).thenComparing(GroupKey::childId);

    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final SummaryRunRequests runRequests;
    private final ConsentStateReader consentStateReader;

    @Transactional(readOnly = true)
    public SummaryGroupBoardResponse getBoard(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        String institutionId = String.valueOf(organizationId);

        MatchingCounts matching = new MatchingCounts(
                journalEntryRepository.countChildUnknown(MATCHING_IN_PROGRESS, institutionId),
                journalEntryRepository.countChildUnknown(AWAITING_MATCH_REVIEW, institutionId));

        Map<GroupKey, Map<JournalEntryStatus, Long>> groups = new TreeMap<>(OLDEST_FIRST);
        for (GroupStatusCount c : journalEntryRepository.countByGroupAndStatus(CARD, institutionId)) {
            groups.computeIfAbsent(new GroupKey(c.childId(), c.entryDate()),
                    k -> new EnumMap<>(JournalEntryStatus.class)).put(c.status(), c.count());
        }
        if (groups.isEmpty()) {
            return new SummaryGroupBoardResponse(matching, List.of());
        }
        Set<Long> onRoster = childOrganizationRepository
                .findByChildIdInAndDeletedAtIsNull(groups.keySet().stream().map(GroupKey::childId)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(link -> link.getOrganizationId().equals(organizationId))
                .map(ChildOrganization::getChildId)
                .collect(Collectors.toSet());
        Set<Long> members = consentStateReader.grantedChildIds(onRoster, organizationId);
        groups.keySet().removeIf(group -> !members.contains(group.childId()));
        if (groups.isEmpty()) {
            return new SummaryGroupBoardResponse(matching, List.of());
        }

        Set<LocalDate> dates = groups.keySet().stream().map(GroupKey::entryDate).collect(Collectors.toSet());
        Set<GroupKey> requested = runRequests.pendingGroupsOf(organizationId, dates).stream()
                .map(g -> new GroupKey(g.childId(), g.entryDate()))
                .collect(Collectors.toSet());
        Set<GroupKey> inReview = new HashSet<>(journalEntryRepository
                .findGroupKeys(Set.of(JournalEntryStatus.GATE1_PENDING), institutionId, dates));
        Map<LocalDate, Long> busyByDate = journalEntryRepository
                .countByDate(SummaryService.IN_PROGRESS, institutionId, dates).stream()
                .collect(Collectors.toMap(DateCount::entryDate, DateCount::count));
        Map<Long, Child> children = childRepository.findAllById(members).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));

        List<SummaryGroupCard> cards = groups.entrySet().stream()
                .map(e -> card(e.getKey(), e.getValue(), children.get(e.getKey().childId()),
                        requested.contains(e.getKey()), inReview.contains(e.getKey()),
                        busyByDate.getOrDefault(e.getKey().entryDate(), 0L)))
                .toList();
        return new SummaryGroupBoardResponse(matching, cards);
    }

    private static SummaryGroupCard card(GroupKey group, Map<JournalEntryStatus, Long> byStatus, Child child,
            boolean requested, boolean inReview, long busy) {
        Counts counts = new Counts(
                count(byStatus, JournalEntryStatus.MATCHED) + count(byStatus, JournalEntryStatus.VALIDATING),
                count(byStatus, JournalEntryStatus.VALIDATION_BLOCKED),
                count(byStatus, JournalEntryStatus.VALIDATED),
                count(byStatus, JournalEntryStatus.SUMMARIZING),
                count(byStatus, JournalEntryStatus.SYSTEM_ERROR));
        boolean summaryUnderway = requested || counts.summarizing() > 0;
        boolean willSummarize = requested && counts.summarizing() == 0
                && counts.ready() + counts.validating() > 0;
        return new SummaryGroupCard(
                group.childId(),
                child.getName(),
                child.getBirthdate(),
                group.entryDate(),
                label(counts, summaryUnderway, willSummarize),
                counts,
                counts.ready() > 0 && !summaryUnderway,
                requested,
                willSummarize ? busy : 0,
                inReview);
    }

    private static String label(Counts c, boolean summaryUnderway, boolean willSummarize) {
        if (c.needsFix() > 0) {
            return NEEDS_FIX;
        }
        if (c.ready() > 0 && !summaryUnderway) {
            return READY;
        }
        if (c.summarizing() > 0 || willSummarize) {
            return SUMMARIZING;
        }
        if (c.validating() > 0) {
            return VALIDATING;
        }
        return ADMIN_CHECK;
    }

    private static long count(Map<JournalEntryStatus, Long> byStatus, JournalEntryStatus status) {
        return byStatus.getOrDefault(status, 0L);
    }
}
