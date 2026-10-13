package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.Approval;
import com.itda.backend.domain.ApprovalDecision;
import com.itda.backend.domain.ApprovalTargetType;
import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildContext;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.Organization;
import com.itda.backend.domain.SummaryResult;
import com.itda.backend.dto.request.InsightAgentRequest;
import com.itda.backend.repository.ApprovalRepository;
import com.itda.backend.repository.ChildContextRepository;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.SummaryResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인사이트 에이전트에 보낼 재료를 모은다 (DB 스키마 §9.2·§9.3).
 *
 * <p>인사이트는 원본 일지가 아니라 <b>Gate 1 을 통과한 요약</b>을 재료로 쓴다. 그런데 {@code child_context}
 * 는 "어느 요약이 승인됐나" 만 가리키고, 에이전트가 받는 것은 본문이 아니라 문장 단위 {@code claims} 라
 * ({@code InsightInput} 에 본문 필드가 없다) {@code summary_result_id} 로 조인해 꺼내야 한다.
 *
 * <p>FK·JPA 연관관계를 쓰지 않으므로(§16) ID 목록으로 나눠 조회한다.
 *
 * <pre>
 * child_context(child_id)        승인된 요약 id
 *   → summary_result(id IN ...)  entry_date · institution_id · claims
 *   → 기간으로 거른다
 *   → CORRECTED 요약 제거
 *   → organization(id IN ...)    institution_type
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightService {

    private final ChildContextRepository childContextRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final ApprovalRepository approvalRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ChildRepository childRepository;
    private final ObjectMapper objectMapper;

    /**
     * 한 아이의 한 기간치를 요청으로 만든다. 재료가 없으면 {@link Optional#empty()} 다 —
     * <b>그때는 에이전트를 부르지 않는다.</b> 빈 {@code claims} 로 부르면 모델만 쓰고 결과는 어차피 비어 있다.
     *
     * @param periodFrom 재료가 될 관찰 날짜 시작 (포함)
     * @param periodTo   재료가 될 관찰 날짜 끝 (포함)
     */
    @Transactional(readOnly = true)
    public Optional<InsightAgentRequest> prepare(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        Child child = childRepository.findByIdAndDeletedAtIsNull(childId).orElse(null);
        if (child == null) {
            log.warn("child not found, insight skipped childId={}", childId);
            return Optional.empty();
        }

        // 동의를 먼저 본다. 비어 있으면 재료를 모으지 않는다 — 모르면 막는다 (CRITERIA §7).
        List<InsightAgentRequest.Institution> consented = consentedInstitutionsOf(childId);
        if (consented.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> consentedIds = consented.stream()
                .map(InsightAgentRequest.Institution::institutionId)
                .collect(Collectors.toSet());

        List<SummaryResult> approved = approvedSummariesIn(childId, periodFrom, periodTo);
        if (approved.isEmpty()) {
            return Optional.empty();
        }

        Map<Long, Organization> institutions = institutionsOf(approved);
        List<InsightAgentRequest.Claim> claims = new ArrayList<>();
        for (SummaryResult summary : approved) {
            if (!consentedIds.contains(summary.getInstitutionId())) {
                // 동의가 없거나 철회된 기관의 기록이다. AI 도 거르지만 BE 가 먼저 막는다 (CRITERIA §7)
                // — 받는 쪽만 막으면 그 기관의 원문 인용이 재료로 들어가 다른 기관으로 흘러간다.
                continue;
            }
            Organization institution = institutions.get(summary.getInstitutionId());
            if (institution == null) {
                // 기관 종류를 못 붙이면 모델이 기관 간 비교를 할 수 없다. 그 요약만 빼고 간다.
                log.warn("institution not found, summary skipped summaryId={} institutionId={}",
                        summary.getId(), summary.getInstitutionId());
                continue;
            }
            claims.addAll(claimsOf(summary, institution));
        }
        if (claims.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new InsightAgentRequest(
                childId, child.getName(), periodFrom.toString(), periodTo.toString(), claims, consented));
    }

    /**
     * 승인된 요약 중 기간 안의 것만. {@code child_context} 에 행이 있다는 것이 곧 Gate 1 통과다.
     *
     * <p>⚠️ <b>교사가 고쳐서 승인한 요약({@code CORRECTED})은 통째로 뺀다</b> (§10.1 #7). 고친 본문은
     * {@code child_context.content} 에만 있고 {@code summary_result.claims} 는 AI 원문 그대로라, 교사가
     * 지운 문장이 근거를 달고 그대로 다른 기관에 나간다. MVP 는 그 요약을 쓰지 않는 쪽으로 정했다 —
     * 최종 본문에 남은 claim 만 살리는 방식은 그다음이다 ({@code AI/insight/CRITERIA.md} §10).
     */
    private List<SummaryResult> approvedSummariesIn(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        // 기간을 먼저 좁힌다. 승인본부터 집으면 그 아이의 승인 이력 전체를 올려 놓고 한 주치만 남기게 된다.
        List<SummaryResult> inPeriod =
                summaryResultRepository.findByChildIdAndEntryDateBetween(childId, periodFrom, periodTo);
        if (inPeriod.isEmpty()) {
            return List.of();
        }

        List<Long> summaryIds = inPeriod.stream().map(SummaryResult::getId).toList();
        Set<Long> approvedIds = childContextRepository.findBySummaryResultIdIn(summaryIds).stream()
                .map(ChildContext::getSummaryResultId)
                .collect(Collectors.toSet());
        if (approvedIds.isEmpty()) {
            return List.of();
        }

        Set<Long> corrected = correctedAmong(summaryIds);
        return inPeriod.stream()
                .filter(summary -> approvedIds.contains(summary.getId()))
                .filter(summary -> !corrected.contains(summary.getId()))
                // child_context 는 승인순이라 8월 기록을 10월에 승인하면 맨 앞에 온다.
                // 인사이트는 기간의 흐름을 보므로 관찰 날짜로 다시 세운다.
                .sorted(Comparator.comparing(SummaryResult::getEntryDate).thenComparing(SummaryResult::getId))
                .toList();
    }

    /** {@code review_type} 을 보지 않는 것은 일부러다 — {@code target_type = SUMMARY} 가 이미 Gate 1 을 뜻한다 (§9.1). */
    private Set<Long> correctedAmong(List<Long> summaryIds) {
        return approvalRepository
                .findByTargetTypeAndDecisionAndTargetIdIn(
                        ApprovalTargetType.SUMMARY, ApprovalDecision.CORRECTED, summaryIds)
                .stream()
                .map(Approval::getTargetId)
                .collect(Collectors.toSet());
    }

    private Map<Long, Organization> institutionsOf(List<SummaryResult> summaries) {
        List<Long> ids = summaries.stream().map(SummaryResult::getInstitutionId).distinct().toList();
        return organizationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
    }

    /**
     * 보호자가 공유에 동의한 기관.
     *
     * <p>⚠️ 아직 {@code sharing_consent} 가 없어서 <b>아이가 지금 다니는 기관 전부</b>를 넣는다 — 계약이
     * 그렇게 정해 뒀다 ({@code AI/insight/schemas.py}). 그 테이블이 생기면 <b>이 메서드만</b> 바꾸면 된다.
     *
     * <p><b>등록 기관과 요약의 기관은 같은 집합이 아니다.</b> {@code child_context} 는 지우지 않고 쌓이기만
     * 해서, 연결을 끊은 기관의 승인본은 그대로 남는다. 그래서 {@code prepare} 가 이 목록으로 재료를 한 번 더
     * 거른다 — 지금 거를 것이 없더라도 끊는 경로가 생기는 날 바로 맞는 동작이 된다.
     */
    private List<InsightAgentRequest.Institution> consentedInstitutionsOf(Long childId) {
        List<Long> organizationIds = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId).stream()
                .map(ChildOrganization::getOrganizationId)
                .distinct()
                .toList();
        return organizationRepository.findAllById(organizationIds).stream()
                .map(organization -> new InsightAgentRequest.Institution(
                        organization.getId(), InstitutionTypeNames.of(organization.getType())))
                .toList();
    }

    /**
     * 요약 한 건의 {@code claims} JSON 을 에이전트 입력으로 편다.
     *
     * <p>{@code claims} 는 TEXT 에 담긴 JSON 문자열이라 SQL 로는 다루지 못한다 —
     * {@code MatchingResultService} 가 {@code candidates} 를 다루는 방식과 같이 여기서 읽는다.
     *
     * <pre>[{ "text": "...", "evidence": [{ "journal_entry_id": 1041, "quote": "...", "span": {...} }] }]</pre>
     *
     * <p><b>배열 순서가 곧 {@code claim_id} 의 뒷자리다.</b> 요약이 배열 순서를 본문 순서로 못 박았으므로
     * 같은 판 안에서는 바뀌지 않는다. 순서를 흐트러뜨리면 인사이트가 가리키는 근거가 어긋난다.
     *
     * <p>{@code span} 은 에이전트 계약에 없어서 버린다.
     */
    private List<InsightAgentRequest.Claim> claimsOf(SummaryResult summary, Organization institution) {
        JsonNode claims = readClaims(summary);
        if (claims == null || !claims.isArray()) {
            return List.of();
        }

        String institutionType = InstitutionTypeNames.of(institution.getType());
        String entryDate = summary.getEntryDate().toString();
        List<InsightAgentRequest.Claim> prepared = new ArrayList<>();
        for (int order = 0; order < claims.size(); order++) {
            JsonNode claim = claims.get(order);
            String text = claim.path("text").asText(null);
            List<InsightAgentRequest.Evidence> evidence = evidenceOf(claim.path("evidence"));
            if (text == null || text.isBlank() || evidence.isEmpty()) {
                // 근거가 끊긴 문장은 보내지 않는다. 원문으로 추적되지 않는 내용이 다른 기관으로 나가면
                // 안 되고(CRITERIA §2), 계약도 evidence 를 최소 1건으로 막아 뒀다 — 보내면 422 다.
                continue;
            }
            prepared.add(new InsightAgentRequest.Claim(
                    summary.getId() + "-" + order, text, entryDate,
                    institution.getId(), institutionType, evidence));
        }
        return prepared;
    }

    private List<InsightAgentRequest.Evidence> evidenceOf(JsonNode evidence) {
        if (!evidence.isArray()) {
            return List.of();
        }
        List<InsightAgentRequest.Evidence> prepared = new ArrayList<>();
        for (JsonNode one : evidence) {
            JsonNode journalEntryId = one.path("journal_entry_id");
            String quote = one.path("quote").asText(null);
            if (!journalEntryId.isNumber() || quote == null || quote.isBlank()) {
                continue;
            }
            prepared.add(new InsightAgentRequest.Evidence(journalEntryId.asLong(), quote));
        }
        return prepared;
    }

    private JsonNode readClaims(SummaryResult summary) {
        String raw = summary.getClaims();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            // AI 가 쓴 값이 깨진 것 — 그 요약만 빼고 나머지로 간다. 한 건 때문에 기간 전체를 버리지 않는다.
            log.warn("failed to parse summary claims, summary skipped summaryId={}", summary.getId(), e);
            return null;
        }
    }
}
