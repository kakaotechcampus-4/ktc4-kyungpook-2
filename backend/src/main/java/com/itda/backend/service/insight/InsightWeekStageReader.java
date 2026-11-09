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

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;

import lombok.RequiredArgsConstructor;

/**
 * 대시보드 진행 현황의 5단계(분석 승인) 판정 (#370). 분석이 끝난 아이×주마다 마지막 회차 인사이트 중 우리 기록이 쓰인
 * 것을 보고, 누가 승인할 차례인지 정한다. 승인 주체는 Gate 2 와 같은 {@link PrimarySource} 다.
 *
 * <p>우선순위: 여기서 승인할 대기 글 &gt; 여기서 보류한 글 &gt; 다른 기관이 승인할 글 &gt; 모두 승인됨. 선생님이 할 일을
 * 먼저 보여준다. 쿼리 수는 주 수와 상관없이 같다(인사이트 한 번, 기관 한 번).
 */
@Service
@RequiredArgsConstructor
public class InsightWeekStageReader {

    private final InsightResultRepository insightResultRepository;
    private final OrganizationRepository organizationRepository;

    /** 우리 기록이 쓰인 인사이트가 있는 주만 답한다. 없는 주는 맵에 없다. */
    @Transactional(readOnly = true)
    public Map<Week, WeekStage> stagesOf(Long organizationId, Collection<Week> weeks) {
        if (weeks.isEmpty()) {
            return Map.of();
        }
        Set<Week> wanted = new HashSet<>(weeks);
        Set<Long> childIds = weeks.stream().map(Week::childId).collect(Collectors.toSet());
        Map<Week, List<InsightResult>> byWeek = InsightGate2QueryService
                .latestRevisionOnly(insightResultRepository.findByChildIdInOrderByIdDesc(childIds)).stream()
                .filter(insight -> PrimarySource.isFedBy(insight, organizationId))
                .filter(insight -> wanted.contains(Week.of(insight)))
                .collect(Collectors.groupingBy(Week::of, LinkedHashMap::new, Collectors.toList()));
        if (byWeek.isEmpty()) {
            return Map.of();
        }

        Set<Long> sourceIds = new HashSet<>();
        byWeek.values().forEach(list -> list.forEach(i -> sourceIds.addAll(PrimarySource.sourceIdsOf(i))));
        Map<Long, Organization> organizations = organizationRepository.findAllById(sourceIds).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));

        Map<Week, WeekStage> stages = new LinkedHashMap<>();
        byWeek.forEach((week, insights) -> stages.put(week, stageOf(organizationId, insights, organizations)));
        return stages;
    }

    private static WeekStage stageOf(Long organizationId, List<InsightResult> insights,
            Map<Long, Organization> organizations) {
        WeekStage held = null;
        WeekStage elsewhere = null;
        // 오래된 글부터 — 목록이 id 내림차순이라 뒤집어 본다. 바로가기가 먼저 만든 글을 연다.
        for (InsightResult insight : insights.reversed()) {
            if (insight.getStatus() == InsightStatus.APPROVED) {
                continue;
            }
            Organization primary = PrimarySource.of(PrimarySource.sourceIdsOf(insight), organizations);
            boolean ours = primary != null && primary.getId().equals(organizationId);
            if (ours && insight.getStatus() == InsightStatus.GATE2_PENDING) {
                return new WeekStage(Stage.REVIEW_HERE, insight.getId(), null);
            }
            if (ours && held == null) {
                held = new WeekStage(Stage.INSIGHT_HELD, insight.getId(), null);
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

    /** 아이×주. */
    public record Week(Long childId, LocalDate from, LocalDate to) {

        static Week of(InsightResult insight) {
            return new Week(insight.getChildId(), insight.getPeriodFrom(), insight.getPeriodTo());
        }
    }

    public enum Stage {
        REVIEW_HERE, INSIGHT_HELD, REVIEW_ELSEWHERE, APPROVED
    }

    /**
     * @param insightId    바로가기로 열 인사이트 — 여기서 처리할 글일 때만
     * @param approverName 승인할 다른 기관 이름 — 다른 기관 차례일 때만
     */
    public record WeekStage(Stage stage, Long insightId, String approverName) {
    }
}
