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

/**
 * 인사이트 진행 현황 (api-spec O-43). 교사가 Gate 1 을 승인한 뒤 Gate 2 에 글이 뜨기까지 화면에 아무 변화가 없어서,
 * 승인한 기록이 지금 어느 단계인지 보여 준다.
 *
 * <p>우리 기관이 <b>최근 {@code lookback-days} 안에 승인한</b> 기록이 속한 (아이, 주)만 본다 — 워커가 다시 볼 주와
 * 같은 창이다 ({@code InsightWorker#candidates}). 다른 기관만 승인한 주는 보이지 않는다.
 *
 * <p>상태는 {@code insight_run} 의 최신 판과 워커의 규칙으로 정한다. 워커는 집기 전에 재료부터 만들어 보고
 * ({@link InsightService#prepare}) 비어 있으면 집지 않으므로, "곧 분석" · "다시 시도" 를 말하기 전에 같은 것을 본다
 * — 안 보면 워커가 영영 집지 않는 주에 그 문구가 계속 남는다. 실패 뒤 대기 시간은 따로 구분하지 않는다 — 기다리든
 * 곧 집든 교사에게는 "다시 시도할 예정" 이다.
 */
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

        // 지금 우리 명부에 있고 공유 동의가 켜진 아이만 — Gate 1 목록과 같은 기준이다 (#332). 연결이 끊기거나
        // 철회된 아이의 진행 상태는 이 기관이 알 일이 아니다.
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
            // 다른 기관 기록으로 분석이 돌았더라도 우리 기록은 들어가지 않았다. 그것을 먼저 알린다.
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
        // 아직 안 집었거나, 실패했거나, 끝난 뒤 새 승인이 생겼다. 워커가 실제로 집을지는 재료가 있어야 정해진다.
        // ponytail: 이 경우만 재료를 만들어 본다(조회 여러 번). 끝난 주는 눈금 조회 한 번 — 화면이 자주 부르면 묶는다.
        if (insightService.prepare(period.childId(), period.from(), period.to()).isPresent()) {
            return latest == null || done ? Status.QUEUED : Status.RETRYING;
        }
        // 동의한 기관의 재료가 없다. 워커는 이 주를 집지 않으니 지난 결과가 마지막이다.
        return done ? countStatus(latest) : Status.NOTHING_NEW;
    }

    private static Status countStatus(InsightRun done) {
        return Objects.requireNonNullElse(done.getInsightCount(), 0) > 0 ? Status.DONE : Status.NOTHING_NEW;
    }

    private record Period(Long childId, LocalDate from, LocalDate to) {
    }
}
