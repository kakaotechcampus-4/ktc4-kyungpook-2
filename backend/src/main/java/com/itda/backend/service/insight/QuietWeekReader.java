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

/**
 * 보호자 리포트에 "특이사항 없음" 으로 보일 주 (#470). 인사이트를 새로 만들지 않고 <b>조회할 때 계산한다</b> — Gate 2 를
 * 거치지 않는 상태 표시라 기관 기록 내용은 나가지 않는다. 그 주에 리포트가 생기면 조건에서 빠져 리포트가 대신 보인다.
 *
 * <p>아이 × 주마다 아래가 <b>모두</b> 맞아야 한다. 하나라도 모르면 빈칸으로 둔다 — 기록을 안 본 주를 "특이사항 없음"
 * 이라고 하면 사실과 다르다.
 * <ul>
 * <li>끝난 주다 (워커와 같은 주 경계, {@link InsightProperties#lastPeriodEnd})</li>
 * <li>주간 인사이트를 허락받고 아이가 다니는 기관이 그 주에 Gate 1 결정을 끝낸 요약이 있다 (승인 · 특이사항 없음 확인)
 * — 기록이 아예 없는 주는 빈칸이다</li>
 * <li>그 주에 안 끝난 일지 · 요약이 모든 기관을 통틀어 없다</li>
 * <li>그 주 인사이트가 하나도 없다 (대기 · 승인 · 보류 모두 — 보류만 있는 주도 교사가 막은 글이 있는 주다)</li>
 * <li>승인된 요약이 있었다면 그 주 분석이 끝났고 0건이었다 ({@link InsightProgressService} 의 {@code NOTHING_NEW} 와 같은 판정)</li>
 * </ul>
 *
 * <p>ponytail: 매칭 전이라 아이가 정해지지 않은 일지는 어느 아이 것인지 몰라 "안 끝난 일지" 로 세지 못한다. 그 일지가
 * 나중에 이 아이로 매칭되면 그 주는 다시 조건에서 빠진다 (그 사이 카드가 잠깐 보일 수 있다).
 */
@Service
@RequiredArgsConstructor
public class QuietWeekReader {

    /** 더 기다릴 것이 없는 일지. 나머지(매칭 · 검증 · 요약 중, 요약 실패, Gate 1 대기 · 보류, 오류)는 안 끝난 것이다. */
    private static final Set<JournalEntryStatus> FINISHED_ENTRIES = EnumSet.of(
            JournalEntryStatus.COMPLETED, JournalEntryStatus.EXCLUDED, JournalEntryStatus.REUPLOAD_REQUESTED,
            JournalEntryStatus.CONSENT_BLOCKED);
    /** 교사가 아직 Gate 1 결정을 하지 않은 요약. */
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

    /** 왜 "특이사항 없음" 인지. */
    public enum Reason {
        /** ① 그 주 기록이 모두 Gate 1 에서 "특이사항 없음" 으로 확인됐다. 승인된 요약이 없어 분석을 돌리지 않았다 */
        NOTHING_NOTABLE,
        /** ② 승인된 요약은 있었지만 분석이 낼 것이 없었다 ({@code insight_run} DONE, 0건) */
        NOTHING_NEW
    }

    public record QuietWeek(Long childId, LocalDate periodFrom, LocalDate periodTo, Reason reason) {
    }

    /** 최근 주부터. */
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
                // 날짜를 못 읽은 일지는 어느 주에도 속하지 않는다 — 요약도 날짜가 있어야 만들어진다.
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
        // 승인된 요약이 있으면 분석이 끝나 0건이어야 한다. 아직 안 돌았거나 · 실패했거나 · 끝난 뒤 새 승인이 생겼으면 기다린다.
        InsightRun latest = insightRunRepository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(week.childId(), week.from(), week.to())
                .orElse(null);
        boolean nothingNew = latest != null && latest.getStatus() == InsightRunStatus.DONE
                && Objects.requireNonNullElse(latest.getInsightCount(), 0) == 0
                && !recorder.materialGrewSince(latest);
        return nothingNew ? new QuietWeek(week.childId(), week.from(), week.to(), Reason.NOTHING_NEW) : null;
    }

    /** 주간 인사이트를 허락받고 아이가 지금 다니는 기관 — 인사이트 재료를 고를 때와 같은 기준이다 ({@link InsightService}). */
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
