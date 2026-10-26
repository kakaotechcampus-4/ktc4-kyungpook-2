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
import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.insight.InsightAgentRequest;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;

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
    private final SharingConsentRepository sharingConsentRepository;
    private final ObjectMapper objectMapper;

    /**
     * 한 아이의 한 기간치를 요청으로 만든다. 재료가 없으면 {@link Optional#empty()} 다 —
     * <b>그때는 에이전트를 부르지 않는다.</b> 빈 {@code claims} 로 부르면 모델만 쓰고 결과는 어차피 비어 있다.
     *
     * @param periodFrom 재료가 될 관찰 날짜 시작 (포함)
     * @param periodTo   재료가 될 관찰 날짜 끝 (포함)
     */
    @Transactional(readOnly = true)
    public Optional<PreparedInsight> prepare(Long childId, LocalDate periodFrom, LocalDate periodTo) {
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

        ApprovedMaterial material = approvedSummariesIn(childId, periodFrom, periodTo);
        List<SummaryResult> approved = material.summaries();
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

        return Optional.of(new PreparedInsight(
                new InsightAgentRequest(childId, child.getName(), periodFrom.toString(), periodTo.toString(),
                        claims, consented),
                material.lastContextId()));
    }

    /**
     * 승인된 요약 중 기간 안의 것만. {@code child_context} 에 행이 있다는 것이 곧 Gate 1 통과다.
     *
     * <p>⚠️ <b>교사가 고쳐서 승인한 요약({@code CORRECTED})은 통째로 뺀다</b> (§10.1 #7). #221 전에는
     * {@code summary_result.claims} 가 AI 원문 그대로라 교사가 지운 문장이 근거를 달고 다른 기관에 나갔다.
     * #221 부터는 Gate 1 승인 때 claims 를 문장 카드 결과로 다시 써서 지운 문장이 남지 않는다 — 이 제외를
     * 없애는 것은 #199 Step 3 이다 ({@code AI/insight/CRITERIA.md} §10).
     */
    /**
     * 재료가 된 요약과, 그것을 고를 때 본 {@code child_context} 중 가장 큰 id.
     *
     * <p>눈금을 따로 조회하지 않고 <b>같은 조회 결과에서</b> 뽑는 이유 — 기본 격리 수준(READ COMMITTED)에서는
     * 한 트랜잭션 안에서도 조회마다 스냅샷이 새로 잡힌다. 다시 조회하면 그 사이 커밋된 승인이 눈금에만 잡히고
     * 재료에는 안 들어가, 그 승인이 "봤다" 로 기록된 채 영영 빠진다 — 이 PR 이 고치는 바로 그 버그다.
     */
    private record ApprovedMaterial(List<SummaryResult> summaries, Long lastContextId) {
        static ApprovedMaterial empty() {
            return new ApprovedMaterial(List.of(), null);
        }
    }

    private ApprovedMaterial approvedSummariesIn(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        // 기간을 먼저 좁힌다. 승인본부터 집으면 그 아이의 승인 이력 전체를 올려 놓고 한 주치만 남기게 된다.
        List<SummaryResult> inPeriod =
                summaryResultRepository.findByChildIdAndEntryDateBetween(childId, periodFrom, periodTo);
        if (inPeriod.isEmpty()) {
            return ApprovedMaterial.empty();
        }

        List<Long> summaryIds = inPeriod.stream().map(SummaryResult::getId).toList();
        List<ChildContext> contexts = childContextRepository.findBySummaryResultIdIn(summaryIds);
        if (contexts.isEmpty()) {
            return ApprovedMaterial.empty();
        }
        Set<Long> approvedIds = contexts.stream()
                .map(ChildContext::getSummaryResultId)
                .collect(Collectors.toSet());
        // 눈금은 방금 읽은 그 행들에서 뽑는다. 다시 조회하면 스냅샷이 달라진다.
        Long lastContextId = contexts.stream().map(ChildContext::getId).max(Long::compareTo).orElseThrow();

        Set<Long> corrected = correctedAmong(summaryIds);
        List<SummaryResult> summaries = inPeriod.stream()
                .filter(summary -> approvedIds.contains(summary.getId()))
                .filter(summary -> !corrected.contains(summary.getId()))
                // child_context 는 승인순이라 8월 기록을 10월에 승인하면 맨 앞에 온다.
                // 인사이트는 기간의 흐름을 보므로 관찰 날짜로 다시 세운다.
                .sorted(Comparator.comparing(SummaryResult::getEntryDate).thenComparing(SummaryResult::getId))
                .toList();
        return new ApprovedMaterial(summaries, lastContextId);
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
     * 보호자가 공유에 동의한 기관 — {@code sharing_consent} 가 ACTIVE 이고, 아이가 지금 그 기관 명부에 있는 곳
     * (DB 스키마 §5.5, {@code AI/insight/CRITERIA.md} §7).
     *
     * <p>명부 소속도 함께 본다. 명부에서 빠질 때 동의를 끄는 경로가 아직 앱에 없어서(테스트 아이 스크립트만 끈다),
     * 동의만 보면 더 이상 다니지 않는 기관이 남을 수 있다. 둘 다 맞아야 넣는다 — 모르면 막는다.
     *
     * <p><b>동의 기관과 요약의 기관은 같은 집합이 아니다.</b> {@code child_context} 는 지우지 않고 쌓이기만
     * 해서, 동의를 철회했거나 연결을 끊은 기관의 승인본은 그대로 남는다. 그래서 {@code prepare} 가 이 목록으로
     * 재료를 한 번 더 거른다.
     */
    private List<InsightAgentRequest.Institution> consentedInstitutionsOf(Long childId) {
        Set<Long> enrolled = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId).stream()
                .map(ChildOrganization::getOrganizationId)
                .collect(Collectors.toSet());
        List<Long> organizationIds = sharingConsentRepository
                .findByChildIdAndStatus(childId, SharingConsentStatus.ACTIVE).stream()
                .map(SharingConsent::getOrganizationId)
                .filter(enrolled::contains)
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
            InsightAgentRequest.ClaimSource source = sourceOf(claim, summary);
            if (source == null) {
                continue;
            }
            List<InsightAgentRequest.Evidence> evidence = evidenceOf(claim.path("evidence"), source);
            if (text == null || text.isBlank() || evidence.isEmpty()) {
                // 근거가 끊긴 문장은 보내지 않는다. 원문으로 추적되지 않는 내용이 다른 기관으로 나가면
                // 안 되고(CRITERIA §2), 계약도 evidence 를 최소 1건으로 막아 뒀다 — 보내면 422 다.
                continue;
            }
            prepared.add(new InsightAgentRequest.Claim(
                    summary.getId() + "-" + order, text, entryDate,
                    institution.getId(), institutionType, source, evidence));
        }
        return prepared;
    }

    /**
     * 문장을 누가 썼는지. {@code source} 가 없으면 {@code AI} 다 — C안(#183) 전에 저장된 요약은 전부 AI 원문이다.
     *
     * <p>모르는 값이면 <b>그 문장을 보내지 않는다</b> ({@code null} 반환). {@code AI} 로 봐 두면 검증을 거치지
     * 않은 교사 문장이 "AI 원문" 으로 나가고, Gate 2 가 띄울 경고도 사라진다. 한 문장을 잃는 쪽이 낫다.
     */
    private InsightAgentRequest.ClaimSource sourceOf(JsonNode claim, SummaryResult summary) {
        JsonNode source = claim.path("source");
        if (source.isMissingNode() || source.isNull()) {
            return InsightAgentRequest.ClaimSource.AI;
        }
        try {
            return InsightAgentRequest.ClaimSource.valueOf(source.asText());
        } catch (IllegalArgumentException e) {
            // asText() 는 객체·배열이면 빈 문자열이라 무엇이 왔는지 안 남는다. 원본 노드를 그대로 찍는다.
            log.warn("unknown claim source, claim dropped summaryId={} source={}", summary.getId(), source);
            return null;
        }
    }

    /**
     * claim 하나의 근거. <b>{@code TEACHER} 문장만 일지 번호를 비울 수 있다</b> — 교사가 Gate 1 에서 새로 쓴
     * 문장은 원본 일지가 없고 {@code quote} 가 그 문장 자체다 (#199, AI 계약 {@code InsightEvidence}).
     *
     * <p>{@code AI}·{@code EDITED} 에서 번호가 빠지면 근거가 끊긴 것이라 그 근거를 버린다. 보내면 계약이
     * 422 로 돌려보내고, 그러면 그 아이의 그 주 인사이트가 통째로 실패한다.
     */
    private List<InsightAgentRequest.Evidence> evidenceOf(JsonNode evidence,
            InsightAgentRequest.ClaimSource source) {
        if (!evidence.isArray()) {
            return List.of();
        }
        boolean entryOptional = source == InsightAgentRequest.ClaimSource.TEACHER;
        List<InsightAgentRequest.Evidence> prepared = new ArrayList<>();
        for (JsonNode one : evidence) {
            JsonNode journalEntryId = one.path("journal_entry_id");
            String quote = one.path("quote").asText(null);
            if (quote == null || quote.isBlank()) {
                continue;
            }
            boolean hasEntry = journalEntryId.isNumber();
            if (!hasEntry && !entryOptional) {
                continue;
            }
            prepared.add(new InsightAgentRequest.Evidence(hasEntry ? journalEntryId.asLong() : null, quote));
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
