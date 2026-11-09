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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대시보드 "선생님 차례"와 "진행 현황" (#370, api-spec O-80). 일지·요약·주간 분석이 지금 어느 단계에서 누구 차례인지
 * BE 가 정해 한 번에 준다 — FE 가 여러 목록 API 를 맞춰 보지 않게.
 *
 * <p>줄 규칙
 * <ul>
 *   <li>검토 승인 전: 아이×날짜 한 줄. 아이를 아직 못 찾은 일지는 날짜마다 한 줄(아이 찾는 중) 또는 일지마다 한
 *       줄(선생님 확인 대기). 막힌 일지는 일지마다, 시스템 오류는 묶음마다 따로 한 줄이다 — 같은 날 다른 일지는 계속
 *       흘러가므로 본 줄과 나눈다.</li>
 *   <li>검토 승인 뒤: 아이×주 한 줄 ({@link InsightProgressService}, 최근 14일 안에 승인한 주). 5단계 승인 주체는
 *       {@link InsightWeekStageReader}.</li>
 * </ul>
 * 아이를 아는 줄은 지금 우리 명부에 있고 우리 기관 동의가 켜진 아이만 보인다 — 묶음 카드(O-34)와 같다. 막힌 일지와
 * 아이 확인 대기는 그 큐(O-22·O-24)와 건수가 맞도록 거르지 않는다.
 *
 * <p>요청마다 쿼리 수는 아이·일지 수와 상관없이 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardPipelineService {

    /** 진행 현황이 읽는 일지 상태. 검토 대기(GATE1_*)는 요약으로, 끝난 일지는 주 줄로 본다. */
    private static final Set<JournalEntryStatus> READ = EnumSet.of(
            JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING, JournalEntryStatus.MATCH_REVIEW,
            JournalEntryStatus.FAILED, JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING,
            JournalEntryStatus.VALIDATION_BLOCKED, JournalEntryStatus.VALIDATED, JournalEntryStatus.SUMMARIZING,
            JournalEntryStatus.SUMMARY_FAILED, JournalEntryStatus.SYSTEM_ERROR);

    /** 아이를 찾는 중. */
    private static final Set<JournalEntryStatus> FINDING_CHILD =
            EnumSet.of(JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING);

    /** 선생님이 아이를 골라야 한다 — 매칭 확인 큐(O-22)와 같은 기준 ({@code JournalEntry#isAwaitingReview}). */
    private static final Set<JournalEntryStatus> AWAITING_MATCH_REVIEW =
            EnumSet.of(JournalEntryStatus.MATCH_REVIEW, JournalEntryStatus.FAILED);

    /** 이 일지가 있는 날짜는 요약을 기다린다 ({@code SummaryService.IN_PROGRESS} 와 같다). */
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
    private final ObjectMapper objectMapper;

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

    /** 지금 우리 명부에 있고 우리 기관 동의가 켜진 아이. 명부로 먼저 좁혀 동의 행을 덜 읽는다. */
    private Set<Long> membersOf(Long organizationId, Set<Long> childIds) {
        if (childIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> onRoster = childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).filter(childIds::contains)
                .collect(Collectors.toSet());
        return onRoster.isEmpty() ? Set.of() : consentStateReader.grantedChildIds(onRoster, organizationId);
    }

    /** 아이를 찾는 중인 일지는 날짜마다 한 줄. 파일 하나가 일지 수십 건으로 나뉘어도 표가 넘치지 않게 센다. */
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
        // 큐 항목은 매칭 결과 행이다. 한 일지에 행이 여럿이면 마지막 것이 큐에 있다.
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
        // 다시 검사하면 판정 행이 쌓인다. 최신순이라 처음 본 것이 큐에 있는 판정이다.
        Map<Long, ValidationResult> latest = new HashMap<>();
        validationResultRepository.findByJournalEntryIdInOrderByIdDesc(blocked.stream().map(PipelineEntry::id)
                .toList()).forEach(v -> latest.putIfAbsent(v.getJournalEntryId(), v));
        return blocked.stream().map(e -> {
            ValidationResult validation = latest.get(e.id());
            List<String> kinds = validation == null ? List.of() : issueTypesOf(validation);
            return entryRow(e.childId(), nameOf(names, e.childId()), e.entryDate(), Status.BLOCKED,
                    new Detail(null, kinds, ValidationReasons.sentencesOf(kinds), null, null),
                    validation == null ? null : target("validation", validation.getId()));
        }).toList();
    }

    /**
     * 아이×날짜 본 줄과 시스템 오류 줄. 검토 대기 판이 있는 묶음은 그 판 한 줄이다 — 새 일지는 그 판을 다시 만드는
     * 재료라 판의 {@code processing} 이 이미 말한다(다시 요약에 실패한 일지만 따로 보인다). 보류한 판은 다시 요약하지
     * 않으므로 새 일지는 따로 보인다.
     */
    private List<Row> entryRows(Long organizationId, List<PipelineEntry> entries, Set<Long> members,
            Map<Long, String> names) {
        List<Row> rows = new ArrayList<>();
        Set<GroupKey> pendingGroups = new HashSet<>();
        // 보드가 쓰는 칸만 읽는다. 대기 줄을 먼저, 보류 줄을 뒤에 넣는다 — 정렬이 같은 순서인 줄은 넣은 순서를 지킨다.
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
                // 다시 요약이 실패하면 원래 일지는 검토 대기로 돌아가고 새 일지만 SUMMARY_FAILED 로 남는다 — 그 실패도 보인다.
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

    /**
     * 한 묶음의 본 줄 상태. 더 앞 단계의 일지가 있으면 그 단계로 본다 — 요약은 묶음 일지가 모두 검증을 마쳐야 시작한다.
     * 요약 실패는 선생님이 정해야 해서 요약 대기보다 먼저 보인다.
     */
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
            // 같은 기관·같은 날짜에 아직 매칭·검증 중인 일지가 있으면 요약 워커가 그 날짜를 기다린다 (#369).
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
            // 분석은 돌았지만 우리 기록이 쓰인 글이 없다 — 우리에게는 새로 알릴 것이 없는 주다.
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

    /** 시스템 오류는 실패한 단계에 둔다 — 검증 중 오류를 "요약"에 두면 어디서 멈췄는지 틀리게 보인다. */
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

    /** 아이 미정 일지는 이름이 없다. {@code Map.of()} 는 null 키를 물으면 예외라 먼저 거른다. */
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

    private List<String> issueTypesOf(ValidationResult validation) {
        String raw = validation.getIssueTypes();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> kinds = objectMapper.readValue(raw, new TypeReference<List<String>>() {
            });
            return kinds == null ? List.of() : kinds;
        } catch (JsonProcessingException e) {
            log.warn("failed to parse validation_result.issue_types id={}", validation.getId(), e);
            return List.of();
        }
    }
}
