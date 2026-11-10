package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.insight.InsightRunStatus;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.insight.InsightRunRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuietWeekReader {

    private static final Set<JournalEntryStatus> FINISHED_ENTRIES = EnumSet.of(
            JournalEntryStatus.COMPLETED, JournalEntryStatus.EXCLUDED, JournalEntryStatus.REUPLOAD_REQUESTED,
            JournalEntryStatus.CONSENT_BLOCKED);
    private static final Set<SummaryStatus> UNDECIDED_SUMMARIES = EnumSet.of(
            SummaryStatus.GENERATED, SummaryStatus.GATE1_PENDING, SummaryStatus.HOLD);

    private final SummaryResultRepository summaryResultRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final InsightResultRepository insightResultRepository;
    private final InsightRunRepository insightRunRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;
    private final InsightResultRecorder recorder;
    private final InsightProperties properties;

    public enum Reason {
        NOTHING_NOTABLE,
        NOTHING_NEW
    }

    public record QuietWeek(Long childId, LocalDate periodFrom, LocalDate periodTo, Reason reason) {
    }

    @Transactional(readOnly = true)
    public List<QuietWeek> read(Collection<Long> childIds) {
        if (childIds.isEmpty()) {
            return List.of();
        }
        LocalDate lastEnd = properties.lastPeriodEnd(LocalDate.now(properties.zone()));
        Map<Week, List<SummaryResult>> summariesByWeek = summaryResultRepository.findByChildIdIn(childIds).stream()
                .collect(Collectors.groupingBy(summary -> weekOf(summary.getChildId(), summary.getEntryDate())));
        Set<Week> unfinished = journalEntryRepository
                .findByChildIdInAndDeletedAtIsNullAndStatusNotIn(childIds, FINISHED_ENTRIES).stream()
                .filter(entry -> entry.getEntryDate() != null)
                .map(entry -> weekOf(entry.getChildId(), entry.getEntryDate()))
                .collect(Collectors.toSet());
        Set<Week> withInsight = insightResultRepository.findByChildIdInOrderByIdDesc(childIds).stream()
                .map(insight -> new Week(insight.getChildId(), insight.getPeriodFrom(), insight.getPeriodTo()))
                .collect(Collectors.toSet());
        Map<Long, Set<Long>> consented = consentedInstitutionsOf(childIds);

        return summariesByWeek.entrySet().stream()
                .filter(entry -> !entry.getKey().to().isAfter(lastEnd))
                .filter(entry -> !unfinished.contains(entry.getKey()) && !withInsight.contains(entry.getKey()))
                .map(entry -> quietWeekOf(entry.getKey(), entry.getValue(),
                        consented.getOrDefault(entry.getKey().childId(), Set.of())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(QuietWeek::periodTo).reversed().thenComparing(QuietWeek::childId))
                .toList();
    }

    private QuietWeek quietWeekOf(Week week, List<SummaryResult> summaries, Set<Long> consented) {
        if (summaries.stream().anyMatch(summary -> UNDECIDED_SUMMARIES.contains(summary.getStatus()))) {
            return null;
        }
        boolean decidedByConsented = summaries.stream()
                .anyMatch(summary -> consented.contains(summary.getInstitutionId())
                        && (summary.getStatus() == SummaryStatus.APPROVED
                                || summary.getStatus() == SummaryStatus.CONFIRMED));
        if (!decidedByConsented) {
            return null;
        }
        if (summaries.stream().noneMatch(summary -> summary.getStatus() == SummaryStatus.APPROVED)) {
            return new QuietWeek(week.childId(), week.from(), week.to(), Reason.NOTHING_NOTABLE);
        }
        InsightRun latest = insightRunRepository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(week.childId(), week.from(), week.to())
                .orElse(null);
        boolean nothingNew = latest != null && latest.getStatus() == InsightRunStatus.DONE
                && Objects.requireNonNullElse(latest.getInsightCount(), 0) == 0
                && !recorder.materialGrewSince(latest);
        return nothingNew ? new QuietWeek(week.childId(), week.from(), week.to(), Reason.NOTHING_NEW) : null;
    }

    private Map<Long, Set<Long>> consentedInstitutionsOf(Collection<Long> childIds) {
        Map<Long, Set<Long>> enrolled = childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.groupingBy(ChildOrganization::getChildId,
                        Collectors.mapping(ChildOrganization::getOrganizationId, Collectors.toSet())));
        return consentStateReader.insightStatesOf(childIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, ofChild -> ofChild.getValue().entrySet().stream()
                        .filter(state -> state.getValue() == ConsentState.GRANTED)
                        .map(Map.Entry::getKey)
                        .filter(enrolled.getOrDefault(ofChild.getKey(), Set.of())::contains)
                        .collect(Collectors.toSet())));
    }

    private Week weekOf(Long childId, LocalDate entryDate) {
        LocalDate to = properties.periodEndOf(entryDate);
        return new Week(childId, properties.periodStartOf(to), to);
    }

    private record Week(Long childId, LocalDate from, LocalDate to) {
    }
}
