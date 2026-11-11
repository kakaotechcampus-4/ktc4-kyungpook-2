package com.itda.backend.service.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.record.FailureStage;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Detail;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Row;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Status;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Target;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Turn;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Week;
import com.itda.backend.dto.response.insight.InsightProgressResponse;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.GroupKey;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.PipelineEntry;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.approval.Gate1BoardItem;
import com.itda.backend.service.approval.SummaryGate1QueryService;
import com.itda.backend.service.child.InstitutionLinkService;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightGate2QueryService;
import com.itda.backend.service.insight.InsightProgressService;
import com.itda.backend.service.insight.InsightWeekStageReader;
import com.itda.backend.service.insight.InsightWeekStageReader.WeekStage;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.validation.ValidationReasons;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardPipelineService {

    private static final Set<JournalEntryStatus> READ = EnumSet.of(
            JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING, JournalEntryStatus.MATCH_REVIEW,
            JournalEntryStatus.FAILED, JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING,
            JournalEntryStatus.VALIDATION_BLOCKED, JournalEntryStatus.VALIDATED, JournalEntryStatus.SUMMARIZING,
            JournalEntryStatus.SUMMARY_FAILED, JournalEntryStatus.SYSTEM_ERROR);

    private static final Set<JournalEntryStatus> FINDING_CHILD =
            EnumSet.of(JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING);

    private static final Set<JournalEntryStatus> AWAITING_MATCH_REVIEW =
            EnumSet.of(JournalEntryStatus.MATCH_REVIEW, JournalEntryStatus.FAILED);

    private static final Set<JournalEntryStatus> HOLDS_DATE = EnumSet.of(JournalEntryStatus.PENDING,
            JournalEntryStatus.MATCHING, JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING);

    private static final Comparator<Row> ORDER = Comparator
            .comparing(Row::who)
            .thenComparingInt(Row::step)
            .thenComparing(DashboardPipelineService::sortDate, Comparator.nullsLast(Comparator.naturalOrder()));

    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;
    private final ValidationResultRepository validationResultRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;
    private final ChildRepository childRepository;
    private final SummaryGate1QueryService summaryGate1QueryService;
    private final InsightProgressService insightProgressService;
    private final InsightWeekStageReader insightWeekStageReader;
    private final InsightGate2QueryService insightGate2QueryService;
    private final InstitutionLinkService institutionLinkService;

    @Transactional(readOnly = true)
    public DashboardPipelineResponse getPipeline(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<PipelineEntry> entries =
                journalEntryRepository.findPipelineEntries(String.valueOf(organizationId), READ);

        Set<Long> childIds = entries.stream().map(PipelineEntry::childId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> members = membersOf(organizationId, childIds);
        Map<Long, String> names = childIds.isEmpty() ? Map.of() : childRepository.findAllById(childIds).stream()
                .collect(Collectors.toMap(Child::getId, Child::getName));

        List<Row> rows = new ArrayList<>();
        rows.addAll(findingChildRows(entries));
        rows.addAll(matchReviewRows(entries, names));
        rows.addAll(blockedRows(entries, names));
        rows.addAll(entryRows(organizationId, entries, members, names));
        rows.addAll(weekRows(organizationId, userId));
        rows.sort(ORDER);

        Turn turn = new Turn(
                count(rows, Status.MATCH_REVIEW),
                count(rows, Status.BLOCKED),
                count(rows, Status.REVIEW) + count(rows, Status.SUMMARY_FAILED),
                insightGate2QueryService.countInsights(organizationId, InsightStatus.GATE2_PENDING),
                institutionLinkService.pendingRequests(userId).size());
        return new DashboardPipelineResponse(turn, rows);
    }

    private Set<Long> membersOf(Long organizationId, Set<Long> childIds) {
        if (childIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> onRoster = childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).filter(childIds::contains)
                .collect(Collectors.toSet());
        return onRoster.isEmpty() ? Set.of() : consentStateReader.grantedChildIds(onRoster, organizationId);
    }

    private static List<Row> findingChildRows(List<PipelineEntry> entries) {
        Map<LocalDate, Integer> byDate = new LinkedHashMap<>();
        entries.stream().filter(e -> FINDING_CHILD.contains(e.status()))
                .forEach(e -> byDate.merge(e.entryDate(), 1, Integer::sum));
        List<Row> rows = new ArrayList<>();
        byDate.forEach((date, count) -> rows.add(entryRow(null, null, date, Status.MATCHING,
                new Detail(count, null, null, null, null), null)));
        return rows;
    }

    private List<Row> matchReviewRows(List<PipelineEntry> entries, Map<Long, String> names) {
        List<PipelineEntry> awaiting = entries.stream().filter(e -> AWAITING_MATCH_REVIEW.contains(e.status()))
                .toList();
        if (awaiting.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> latest = matchingResultRepository
                .findByJournalEntryIdIn(awaiting.stream().map(PipelineEntry::id).toList()).stream()
                .collect(Collectors.toMap(MatchingResult::getJournalEntryId, MatchingResult::getId, Math::max));
        return awaiting.stream()
                .map(e -> entryRow(e.childId(), nameOf(names, e.childId()), e.entryDate(), Status.MATCH_REVIEW, null,
                        target("matching", latest.get(e.id()))))
                .toList();
    }

    private List<Row> blockedRows(List<PipelineEntry> entries, Map<Long, String> names) {
        List<PipelineEntry> blocked = entries.stream()
                .filter(e -> e.status() == JournalEntryStatus.VALIDATION_BLOCKED).toList();
        if (blocked.isEmpty()) {
            return List.of();
        }
        Map<Long, ValidationResult> latest = new HashMap<>();
        validationResultRepository.findByJournalEntryIdInOrderByIdDesc(blocked.stream().map(PipelineEntry::id)
                .toList()).forEach(v -> latest.putIfAbsent(v.getJournalEntryId(), v));
        return blocked.stream().map(e -> {
            ValidationResult validation = latest.get(e.id());
            List<String> kinds = validation == null ? List.of()
                    : JsonColumns.readTexts(validation.getIssueTypes(), "validation_result.issue_types",
                            validation.getId());
            return entryRow(e.childId(), nameOf(names, e.childId()), e.entryDate(), Status.BLOCKED,
                    new Detail(null, kinds, ValidationReasons.sentencesOf(kinds), null, null),
                    validation == null ? null : target("validation", validation.getId()));
        }).toList();
    }

    private List<Row> entryRows(Long organizationId, List<PipelineEntry> entries, Set<Long> members,
            Map<Long, String> names) {
        List<Row> rows = new ArrayList<>();
        Set<GroupKey> pendingGroups = new HashSet<>();
        List<Gate1BoardItem> board = summaryGate1QueryService.getBoardItems(organizationId);
        for (Gate1BoardItem summary : board) {
            if (summary.status() != SummaryStatus.GATE1_PENDING) {
                continue;
            }
            pendingGroups.add(new GroupKey(summary.childId(), summary.entryDate()));
            rows.add(summary.processing()
                    ? summaryRow(summary, Status.REGENERATING, null)
                    : summaryRow(summary, Status.REVIEW, target("summary", summary.id())));
        }
        for (Gate1BoardItem summary : board) {
            if (summary.status() == SummaryStatus.HOLD) {
                rows.add(summaryRow(summary, Status.REVIEW_HELD, target("summary", summary.id())));
            }
        }

        Set<LocalDate> busyDates = entries.stream().filter(e -> HOLDS_DATE.contains(e.status()))
                .map(PipelineEntry::entryDate).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<GroupKey, List<PipelineEntry>> groups = entries.stream()
                .filter(e -> e.childId() != null && members.contains(e.childId()))
                .collect(Collectors.groupingBy(e -> new GroupKey(e.childId(), e.entryDate()), LinkedHashMap::new,
                        Collectors.toList()));
        groups.forEach((group, groupEntries) -> {
            String name = names.get(group.childId());
            groupEntries.stream().filter(e -> e.status() == JournalEntryStatus.SYSTEM_ERROR).findFirst()
                    .ifPresent(e -> rows.add(systemErrorRow(group, name, e.failureStage())));
            if (pendingGroups.contains(group)) {
                if (groupEntries.stream().anyMatch(e -> e.status() == JournalEntryStatus.SUMMARY_FAILED)) {
                    rows.add(entryRow(group.childId(), name, group.entryDate(), Status.SUMMARY_FAILED, null,
                            new Target("summary_failed", null)));
                }
                return;
            }
            Status status = mainStatusOf(groupEntries, busyDates.contains(group.entryDate()));
            if (status != null) {
                rows.add(entryRow(group.childId(), name, group.entryDate(), status, null,
                        status == Status.SUMMARY_FAILED ? new Target("summary_failed", null) : null));
            }
        });
        return rows;
    }

    private static Status mainStatusOf(List<PipelineEntry> entries, boolean dateBusy) {
        Set<JournalEntryStatus> statuses = entries.stream().map(PipelineEntry::status)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(JournalEntryStatus.class)));
        if (statuses.contains(JournalEntryStatus.MATCHED) || statuses.contains(JournalEntryStatus.VALIDATING)) {
            return Status.VALIDATING;
        }
        if (statuses.contains(JournalEntryStatus.SUMMARIZING)) {
            return Status.SUMMARIZING;
        }
        if (statuses.contains(JournalEntryStatus.SUMMARY_FAILED)) {
            return Status.SUMMARY_FAILED;
        }
        if (statuses.contains(JournalEntryStatus.VALIDATED)) {
            return dateBusy ? Status.WAITING_SAME_DATE : Status.SUMMARIZING;
        }
        return null;
    }

    private List<Row> weekRows(Long organizationId, String userId) {
        List<InsightProgressResponse> progress = insightProgressService.getProgress(userId);
        List<InsightWeekStageReader.Week> done = progress.stream()
                .filter(p -> p.status() == InsightProgressResponse.Status.DONE)
                .map(DashboardPipelineService::weekOf)
                .toList();
        Map<InsightWeekStageReader.Week, WeekStage> stages = insightWeekStageReader.stagesOf(organizationId, done);
        return progress.stream().map(p -> weekRow(p, stages.get(weekOf(p)))).toList();
    }

    private static Row weekRow(InsightProgressResponse progress, WeekStage stage) {
        Status status;
        Detail detail = null;
        Target target = null;
        if (progress.status() != InsightProgressResponse.Status.DONE) {
            status = Status.valueOf(progress.status().name());
        } else if (stage == null) {
            status = Status.NOTHING_NEW;
        } else {
            status = switch (stage.stage()) {
                case REVIEW_HERE -> Status.REVIEW_HERE;
                case INSIGHT_HELD -> Status.INSIGHT_HELD;
                case REVIEW_ELSEWHERE -> Status.REVIEW_ELSEWHERE;
                case APPROVED -> Status.APPROVED;
            };
            if (stage.insightId() != null) {
                target = target("insight", stage.insightId());
            }
            if (stage.approverName() != null) {
                detail = new Detail(null, null, null, null, stage.approverName());
            }
        }
        Week week = new Week(LocalDate.parse(progress.periodFrom()), LocalDate.parse(progress.periodTo()),
                progress.period());
        return new Row(Kind.WEEK, progress.childId(), progress.childName(), null, week, status.step(),
                status.who(), status, detail, target);
    }

    private static InsightWeekStageReader.Week weekOf(InsightProgressResponse progress) {
        return new InsightWeekStageReader.Week(Long.valueOf(progress.childId()),
                LocalDate.parse(progress.periodFrom()), LocalDate.parse(progress.periodTo()));
    }

    private static Row summaryRow(Gate1BoardItem summary, Status status, Target target) {
        Detail detail = summary.noContent() ? new Detail(null, null, null, true, null) : null;
        return entryRow(summary.childId(), summary.childName(), summary.entryDate(), status, detail, target);
    }

    private static Row systemErrorRow(GroupKey group, String name, FailureStage stage) {
        int step = stage == FailureStage.VALIDATION ? 1 : Status.SYSTEM_ERROR.step();
        return new Row(Kind.ENTRY, idOf(group.childId()), name, group.entryDate(), null, step,
                Status.SYSTEM_ERROR.who(), Status.SYSTEM_ERROR, null, null);
    }

    private static Row entryRow(Long childId, String childName, LocalDate date, Status status, Detail detail,
            Target target) {
        return new Row(Kind.ENTRY, idOf(childId), childName, date, null, status.step(), status.who(), status,
                detail, target);
    }

    private static Target target(String type, Long id) {
        return id == null ? null : new Target(type, String.valueOf(id));
    }

    private static String nameOf(Map<Long, String> names, Long childId) {
        return childId == null ? null : names.get(childId);
    }

    private static String idOf(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private static LocalDate sortDate(Row row) {
        return row.week() != null ? row.week().from() : row.date();
    }

    private static int count(Collection<Row> rows, Status status) {
        return (int) rows.stream().filter(row -> row.status() == status).count();
    }
}
