package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.repository.insight.InsightKey;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InsightWeekStageReader {

    private final InsightResultRepository insightResultRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public Map<Week, WeekStage> stagesOf(Long organizationId, Collection<Week> weeks) {
        if (weeks.isEmpty()) {
            return Map.of();
        }
        Set<Week> wanted = new HashSet<>(weeks);
        Map<Week, List<InsightKey>> byWeek = insightResultRepository.findLatestKeysForPeriods(
                        weeks.stream().map(Week::childId).collect(Collectors.toSet()),
                        weeks.stream().map(Week::from).collect(Collectors.toSet()),
                        weeks.stream().map(Week::to).collect(Collectors.toSet())).stream()
                .filter(insight -> wanted.contains(Week.of(insight)))
                .filter(insight -> sourceIdsOf(insight).contains(organizationId))
                .collect(Collectors.groupingBy(Week::of, LinkedHashMap::new, Collectors.toList()));
        if (byWeek.isEmpty()) {
            return Map.of();
        }

        Set<Long> sourceIds = new HashSet<>();
        byWeek.values().forEach(list -> list.forEach(i -> sourceIds.addAll(sourceIdsOf(i))));
        Map<Long, Organization> organizations = organizationRepository.findAllById(sourceIds).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));

        Map<Week, WeekStage> stages = new LinkedHashMap<>();
        byWeek.forEach((week, insights) -> stages.put(week, stageOf(organizationId, insights, organizations)));
        return stages;
    }

    private static List<Long> sourceIdsOf(InsightKey insight) {
        return PrimarySource.sourceIdsOf(insight.id(), insight.sourceInstitutionIds());
    }

    private static WeekStage stageOf(Long organizationId, List<InsightKey> insights,
            Map<Long, Organization> organizations) {
        WeekStage held = null;
        WeekStage elsewhere = null;
        for (InsightKey insight : insights.reversed()) {
            if (insight.status() == InsightStatus.APPROVED) {
                continue;
            }
            Organization primary = PrimarySource.of(sourceIdsOf(insight), organizations);
            boolean ours = primary != null && primary.getId().equals(organizationId);
            if (ours && insight.status() == InsightStatus.GATE2_PENDING) {
                return new WeekStage(Stage.REVIEW_HERE, insight.id(), null);
            }
            if (ours && held == null) {
                held = new WeekStage(Stage.INSIGHT_HELD, insight.id(), null);
            }
            if (!ours && elsewhere == null) {
                elsewhere = new WeekStage(Stage.REVIEW_ELSEWHERE, null, primary == null ? null : primary.getName());
            }
        }
        if (held != null) {
            return held;
        }
        return elsewhere != null ? elsewhere : new WeekStage(Stage.APPROVED, null, null);
    }

    public record Week(Long childId, LocalDate from, LocalDate to) {

        static Week of(InsightKey insight) {
            return new Week(insight.childId(), insight.periodFrom(), insight.periodTo());
        }
    }

    public enum Stage {
        REVIEW_HERE, INSIGHT_HELD, REVIEW_ELSEWHERE, APPROVED
    }

    public record WeekStage(Stage stage, Long insightId, String approverName) {
    }
}
