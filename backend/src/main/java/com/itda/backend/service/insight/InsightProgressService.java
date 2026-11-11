package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.insight.InsightRunStatus;
import com.itda.backend.dto.response.insight.InsightProgressResponse;
import com.itda.backend.dto.response.insight.InsightProgressResponse.Status;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightRunRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InsightProgressService {

    private final UserService userService;
    private final ChildContextRepository childContextRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChildRepository childRepository;
    private final InsightRunRepository insightRunRepository;
    private final ConsentStateReader consentStateReader;
    private final InsightProperties properties;
    private final InsightResultRecorder recorder;
    private final InsightService insightService;

    @Transactional(readOnly = true)
    public List<InsightProgressResponse> getProgress(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<Period> periods = childContextRepository
                .findApprovedEntryDaysSinceByInstitution(organizationId,
                        LocalDateTime.now().minusDays(properties.lookbackDays()))
                .stream()
                .map(day -> {
                    LocalDate to = properties.periodEndOf(day.entryDate());
                    return new Period(day.childId(), properties.periodStartOf(to), to);
                })
                .distinct()
                .toList();
        if (periods.isEmpty()) {
            return List.of();
        }

        Set<Long> enrolled = childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).collect(Collectors.toSet());
        Set<Long> visible = consentStateReader.grantedChildIds(
                periods.stream().map(Period::childId).filter(enrolled::contains).collect(Collectors.toSet()),
                organizationId);
        Map<Long, String> names = childRepository.findAllById(visible).stream()
                .filter(child -> child.getDeletedAt() == null)
                .collect(Collectors.toMap(Child::getId, Child::getName));
        if (names.isEmpty()) {
            return List.of();
        }

        Map<Long, Map<Long, ConsentState>> insightConsents = consentStateReader.insightStatesOf(names.keySet());
        LocalDate oldestEnd = periods.stream().map(Period::to).min(Comparator.naturalOrder()).orElseThrow();
        Map<Period, InsightRun> latestRuns = insightRunRepository
                .findByChildIdInAndPeriodToGreaterThanEqual(names.keySet(), oldestEnd).stream()
                .collect(Collectors.toMap(
                        run -> new Period(run.getChildId(), run.getPeriodFrom(), run.getPeriodTo()),
                        Function.identity(),
                        (a, b) -> a.getRevision() >= b.getRevision() ? a : b));
        LocalDate lastEnd = properties.lastPeriodEnd(LocalDate.now(properties.zone()));

        return periods.stream()
                .filter(period -> names.containsKey(period.childId()))
                .sorted(Comparator.comparing(Period::to).reversed()
                        .thenComparing(period -> names.get(period.childId())))
                .map(period -> InsightProgressResponse.of(
                        period.childId(),
                        names.get(period.childId()),
                        period.from().toString(),
                        period.to().toString(),
                        InsightGate2QueryService.periodLabel(period.from()),
                        statusOf(period, latestRuns.get(period), lastEnd,
                                insightConsents.getOrDefault(period.childId(), Map.of())
                                        .getOrDefault(organizationId, ConsentState.NOT_GRANTED))))
                .toList();
    }

    private Status statusOf(Period period, InsightRun latest, LocalDate lastEnd, ConsentState insightConsent) {
        if (insightConsent != ConsentState.GRANTED) {
            return Status.NOT_CONSENTED;
        }
        if (period.to().isAfter(lastEnd)) {
            return Status.WAITING_WEEK_END;
        }
        if (latest != null && latest.getStatus() == InsightRunStatus.RUNNING) {
            return Status.RUNNING;
        }
        boolean done = latest != null && latest.getStatus() == InsightRunStatus.DONE;
        if (done && !recorder.materialGrewSince(latest)) {
            return countStatus(latest);
        }
        if (insightService.prepare(period.childId(), period.from(), period.to()).isPresent()) {
            return latest == null || done ? Status.QUEUED : Status.RETRYING;
        }
        return done ? countStatus(latest) : Status.NOTHING_NEW;
    }

    private static Status countStatus(InsightRun done) {
        return Objects.requireNonNullElse(done.getInsightCount(), 0) > 0 ? Status.DONE : Status.NOTHING_NEW;
    }

    private record Period(Long childId, LocalDate from, LocalDate to) {
    }
}
