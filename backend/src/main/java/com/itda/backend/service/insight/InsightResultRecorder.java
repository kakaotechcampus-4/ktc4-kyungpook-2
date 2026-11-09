package com.itda.backend.service.insight;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightRun;
import com.itda.backend.domain.insight.InsightRunStatus;
import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.dto.response.insight.InsightAgentResponse;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.insight.InsightRunRepository;
import com.itda.backend.repository.summary.ChildContextRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightResultRecorder {

    static final String STUCK = "stuck";

    private final InsightResultRepository insightResultRepository;
    private final InsightRunRepository insightRunRepository;
    private final ChildContextRepository childContextRepository;
    private final InsightProperties properties;

    @Transactional(readOnly = true)
    public boolean needsRun(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        return shouldRun(latestRun(childId, periodFrom, periodTo), childId, periodFrom, periodTo);
    }

    private InsightRun latestRun(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        return insightRunRepository
                .findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(childId, periodFrom, periodTo)
                .orElse(null);
    }

    private boolean shouldRun(InsightRun latest, Long childId, LocalDate periodFrom, LocalDate periodTo) {
        if (latest == null) {
            return true;
        }
        if (latest.getStatus() == InsightRunStatus.FAILED) {
            return !coolingDown(latest, childId, periodFrom, periodTo);
        }
        if (latest.getStatus() == InsightRunStatus.RUNNING) {
            return false;
        }
        return materialGrewSince(latest);
    }

    boolean materialGrewSince(InsightRun done) {
        if (done.getLastContextId() == null) {
            return true;
        }
        Long current = childContextRepository.findMaxIdInPeriod(done.getChildId(), done.getPeriodFrom(),
                done.getPeriodTo());
        return current != null && current > done.getLastContextId();
    }

    private boolean coolingDown(InsightRun latest, Long childId, LocalDate periodFrom, LocalDate periodTo) {
        List<Duration> backoffs = properties.failureBackoffs();
        if (backoffs == null || backoffs.isEmpty()) {
            return false;
        }
        int failures = (int) insightRunRepository
                .findByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(childId, periodFrom, periodTo).stream()
                .takeWhile(run -> run.getStatus() == InsightRunStatus.FAILED)
                .count();
        Duration wait = backoffs.get(Math.min(failures, backoffs.size()) - 1);
        return latest.getUpdatedAt().plus(wait).isAfter(LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InsightRun> claim(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        InsightRun previous = latestRun(childId, periodFrom, periodTo);
        if (!shouldRun(previous, childId, periodFrom, periodTo)) {
            return Optional.empty();
        }

        int revision = previous == null ? 1 : previous.getRevision() + 1;
        return Optional.of(insightRunRepository
                .saveAndFlush(InsightRun.claim(childId, periodFrom, periodTo, revision)));
    }

    @Transactional
    public int releaseStuck() {
        List<InsightRun> stuck = insightRunRepository.findByStatus(InsightRunStatus.RUNNING);
        stuck.forEach(run -> run.fail(STUCK));
        insightRunRepository.saveAll(stuck);
        return stuck.size();
    }

    @Transactional
    public void record(InsightRun run, InsightAgentResponse response, String rawJson, Long lastContextId,
            InsightAgentRequest request) {
        Map<String, Long> institutionOfClaim = new HashMap<>();
        request.claims().forEach(c -> institutionOfClaim.put(c.claimId(), c.institutionId()));
        request.historyClaims().forEach(c -> institutionOfClaim.put(c.claimId(), c.institutionId()));
        List<InsightAgentResponse.Insight> insights =
                response.insights() == null ? List.of() : response.insights();

        List<InsightResult> saved = new ArrayList<>();
        for (InsightAgentResponse.Insight insight : insights) {
            if (isBlank(insight.situation()) || isBlank(insight.behavior()) || isBlank(insight.content())) {
                log.warn("insight missing required text, dropped childId={} period={}..{}",
                        run.getChildId(), run.getPeriodFrom(), run.getPeriodTo());
                continue;
            }
            saved.add(toEntity(run, insight, rawJson, institutionOfClaim));
        }
        insightResultRepository.saveAll(saved);
        if (saved.isEmpty()) {
            log.info("insight run produced nothing childId={} period={}..{} claims={}",
                    run.getChildId(), run.getPeriodFrom(), run.getPeriodTo(), request.claims().size());
        }

        run.finish(saved.size(), lastContextId);
        insightRunRepository.save(run);
    }

    @Transactional
    public void recordFailure(InsightRun run, String reason) {
        run.fail(reason);
        insightRunRepository.save(run);
    }

    private InsightResult toEntity(InsightRun run, InsightAgentResponse.Insight insight, String rawJson,
            Map<String, Long> institutionOfClaim) {
        LocalDate from = run.getPeriodFrom();
        LocalDate to = run.getPeriodTo();
        return InsightResult.of(run.getChildId(), from, to, run.getRevision(),
                insight.situation(), insight.behavior(), insight.content(),
                json(insight.supports()), json(insight.claimIds()),
                json(insight.sourceEntryIds()), sourceInstitutionsOf(insight, institutionOfClaim),
                Boolean.TRUE.equals(insight.crossInstitution()), rawJson, json(insight.lookedUpClaimIds()));
    }

    private String sourceInstitutionsOf(InsightAgentResponse.Insight insight, Map<String, Long> institutionOfClaim) {
        Set<Long> ids = new LinkedHashSet<>();
        JsonNode given = insight.sourceInstitutionIds();
        if (given != null && given.isArray()) {
            given.forEach(node -> {
                if (node.canConvertToLong()) {
                    ids.add(node.asLong());
                }
            });
        }
        List<JsonNode> cited = new ArrayList<>();
        cited.add(insight.claimIds());
        cited.add(insight.lookedUpClaimIds());
        if (insight.supports() != null && insight.supports().isArray()) {
            insight.supports().forEach(support -> cited.add(support.path("claim_ids")));
        }
        for (JsonNode claimIds : cited) {
            if (claimIds == null || !claimIds.isArray()) {
                continue;
            }
            claimIds.forEach(node -> {
                Long institutionId = institutionOfClaim.get(node.asText());
                if (institutionId != null) {
                    ids.add(institutionId);
                }
            });
        }
        return ids.toString().replace(" ", "");
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
