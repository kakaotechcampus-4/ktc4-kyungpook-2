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
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 오늘의 업무 묶음 카드 (O-34). 요약 전 묶음(아동 × 날짜)마다 일지가 어디까지 왔는지와 교사가 할 일을 보여준다.
 *
 * <p>요약이 만들어지면 묶음은 1차 검토(O-30)로 옮겨 가고 카드는 없어진다. 그래서 남은 카드가 곧 밀린 일이라 날짜로
 * 자르지 않고 전부 준다. 아동이 아직 정해지지 않은 일지는 어느 카드인지 몰라 건수만 따로 준다. 기관에서 뺀 아이는
 * [요약](O-32)이 거절하므로 카드를 띄우지 않는다.
 *
 * <p>라벨은 가장 덜 진행된 일지가 아니라 교사가 할 일을 먼저 보여준다 — 수정 요청, 요약 대기 순이다. 할 일이 없으면
 * 진행 중인 단계를 보여준다. 다른 상태의 일지가 섞여 있어도 요약 대기 일지가 있으면 [요약]을 누를 수 있다.
 *
 * <p>묶음·상태별 건수를 한 번에 세고 코드에서 맞춘다 — 카드 수와 상관없이 SQL 수가 같다.
 */
@Service
@RequiredArgsConstructor
public class SummaryGroupQueryService {

    private static final String NEEDS_FIX = "needs_fix";
    private static final String READY = "ready";
    private static final String SUMMARIZING = "summarizing";
    private static final String VALIDATING = "validating";
    private static final String ADMIN_CHECK = "admin_check";

    /**
     * 카드를 만드는 상태 — 아동·날짜가 정해졌고 아직 요약 전이다. 요약 실패({@code SUMMARY_FAILED})는 1차 검토로
     * 보내기로 해서 넣지 않는다. 수정 요청에서 끝낸 일지와 끝난 일지도 넣지 않는다.
     */
    private static final Set<JournalEntryStatus> CARD = Set.of(JournalEntryStatus.MATCHED,
            JournalEntryStatus.VALIDATING, JournalEntryStatus.VALIDATION_BLOCKED, JournalEntryStatus.VALIDATED,
            JournalEntryStatus.SUMMARIZING, JournalEntryStatus.SYSTEM_ERROR);

    private static final Set<JournalEntryStatus> MATCHING_IN_PROGRESS =
            Set.of(JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING);

    /** 확인 필요 큐에서 아이를 골라야 하는 일지 ({@code JournalEntry#isAwaitingReview}). */
    private static final Set<JournalEntryStatus> AWAITING_MATCH_REVIEW =
            Set.of(JournalEntryStatus.MATCH_REVIEW, JournalEntryStatus.FAILED);

    private static final Comparator<GroupKey> OLDEST_FIRST =
            Comparator.comparing(GroupKey::entryDate).thenComparing(GroupKey::childId);

    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final SummaryRunRequests runRequests;

    /** 요청 교사 기관의 요약 전 묶음 카드를 오래된 날짜부터. */
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
        Set<Long> members = childOrganizationRepository
                .findByChildIdInAndDeletedAtIsNull(groups.keySet().stream().map(GroupKey::childId).toList())
                .stream()
                .filter(link -> link.getOrganizationId().equals(organizationId))
                .map(ChildOrganization::getChildId)
                .collect(Collectors.toSet());
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
        // 요약은 같은 기관·날짜에 매칭·검증 중인 일지가 있으면 기다린다 (SummaryService.isReady). 그 건수다.
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
        // 누른 요청은 워커가 집기 전까지 그 뒤에 들어온 요약 대기 일지도 함께 맡는다. 요약 중에 들어온 일지는 이번 요약에
        // 없고, 요약을 남길 때 그때까지 누른 요청이 닫히므로 다시 눌러야 한다.
        boolean awaitingStart = requested && counts.summarizing() == 0;
        boolean willSummarize = awaitingStart && counts.ready() + counts.validating() > 0;
        return new SummaryGroupCard(
                group.childId(),
                child.getName(),
                child.getBirthdate(),
                group.entryDate(),
                label(counts, awaitingStart, willSummarize),
                counts,
                counts.ready() > 0 && !awaitingStart,
                requested,
                willSummarize ? busy : 0,
                inReview);
    }

    /** 할 일 우선. 눌러 두고 시작을 기다리는 묶음은 요약 중으로 본다 — 교사가 할 일은 끝났다. */
    private static String label(Counts c, boolean awaitingStart, boolean willSummarize) {
        if (c.needsFix() > 0) {
            return NEEDS_FIX;
        }
        if (c.ready() > 0 && !awaitingStart) {
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
