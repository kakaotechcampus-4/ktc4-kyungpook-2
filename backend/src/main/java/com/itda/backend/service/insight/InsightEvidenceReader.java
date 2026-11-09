package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.global.json.JsonColumns;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인사이트의 근거가 된 승인 요약을 읽는다. Gate 2 검토(O-40 · O-41)와 보호자 리포트(G-35 · G-36)가 같이 쓴다 (#405) —
 * 교사가 보고 승인한 근거와 보호자가 받는 근거를 다르게 고르지 않게 한 곳에 둔다.
 *
 * <p>근거 요약은 {@code claim_ids} 의 요약과, {@code looked_up_claim_ids} 중 본문이나 대처에서 <b>실제로 인용한</b> 지난
 * 기록 요약이다 (#387). 요약마다 승인본({@code child_context})·관찰 날짜·기관을 붙인다. 승인본이 없는 요약과 다른 아이의
 * 요약은 뺀다. 기관 행이 남아 있는지, 누구에게 보여도 되는지는 부르는 쪽이 본다.
 *
 * <p>AI 가 쓴 JSON 이 깨져 있어도 한 건 때문에 목록 전체가 500 나지 않게 빈 값으로 읽는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsightEvidenceReader {

    private final SummaryResultRepository summaryResultRepository;
    private final ChildContextRepository childContextRepository;

    /**
     * 인사이트별 근거. {@code claim_ids} 는 {@code "{summary_result.id}-{claims 배열 순서}"} 라 앞자리로
     * 요약을 찾고, 그 요약의 승인본({@code child_context})·날짜·기관을 붙인다. 승인본이 없는 요약은 뺀다 —
     * Gate 1 을 통과하지 않은 글은 근거가 아니다. 인사이트 수와 상관없이 질의 둘이다.
     *
     * @return 인사이트 id → 근거. 근거 번호가 하나도 없으면 빠질 수 있다 — {@link Evidence#EMPTY} 로 읽는다
     */
    public Map<Long, Evidence> read(List<InsightResult> insights) {
        Map<Long, List<Long>> summaryIdsOf = new HashMap<>();
        Map<Long, Set<Long>> lookedUpOf = new HashMap<>();
        Set<Long> allSummaryIds = new HashSet<>();
        for (InsightResult insight : insights) {
            List<Long> ids = summaryIdsIn(insight);
            Set<Long> lookedUp = lookedUpSummaryIdsOf(insight);
            lookedUpOf.put(insight.getId(), lookedUp);
            // 지난 기록은 대처 근거에만 들어 있을 수도 있다. 근거 목록에서 빠지면 "지난 기록을 찾아 씀" 이 화면에 안 보인다.
            lookedUp.stream().filter(id -> !ids.contains(id)).forEach(ids::add);
            summaryIdsOf.put(insight.getId(), ids);
            allSummaryIds.addAll(ids);
            // 대처 근거의 요약도 읽는다 — 교사 문장인지 볼 때만 쓴다.
            allClaimRefs(insight).forEach(ref -> allSummaryIds.add(ref.summaryId()));
        }
        if (allSummaryIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SummaryResult> summaries = summaryResultRepository.findAllById(allSummaryIds).stream()
                .collect(Collectors.toMap(SummaryResult::getId, s -> s));
        Map<Long, Long> contextIdOf = childContextRepository.findBySummaryResultIdIn(allSummaryIds).stream()
                .collect(Collectors.toMap(ChildContext::getSummaryResultId, ChildContext::getId));

        Map<Long, Evidence> evidences = new HashMap<>();
        for (InsightResult insight : insights) {
            Set<Long> lookedUp = lookedUpOf.get(insight.getId());
            List<Ref> refs = new ArrayList<>();
            for (Long summaryId : summaryIdsOf.getOrDefault(insight.getId(), List.of())) {
                SummaryResult summary = summaries.get(summaryId);
                Long contextId = contextIdOf.get(summaryId);
                if (summary == null || contextId == null) {
                    continue;
                }
                if (!summary.getChildId().equals(insight.getChildId())) {
                    // claim_id 는 AI 가 쓴 글자다. 숫자 하나가 어긋나면 다른 아이의 요약을 가리키고, 그
                    // 아이의 기관·날짜·승인본 id 가 아무 상관 없는 교사·보호자의 화면에 뜬다. 규칙으로 막는다
                    // (CLAUDE.md: 접근 판단을 LLM 동작에 맡기지 않는다).
                    log.warn("claim_id points at another child's summary, dropped insightId={} summaryId={}",
                            insight.getId(), summaryId);
                    continue;
                }
                // 지난 기록 표시는 날짜로 한 번 더 본다 — AI 가 이번 기간 기록을 잘못 넣어도 "지난 기록" 으로 나가지 않게.
                boolean past = lookedUp.contains(summaryId) && summary.getEntryDate().isBefore(insight.getPeriodFrom());
                refs.add(new Ref(contextId, summary.getEntryDate(), summary.getInstitutionId(), past,
                        sentencesOf(insight, summary)));
            }
            evidences.put(insight.getId(), new Evidence(refs, hasTeacherClaim(insight, summaries)));
        }
        return evidences;
    }

    /**
     * 그 요약에서 인사이트가 실제로 인용한 문장들. 인용 순서대로, 같은 문장은 한 번만.
     *
     * <p>{@code claim_ids} 에서 온 것이 {@code "상황"}, {@code supports[].claim_ids} 에서 온 것이
     * {@code "대처"} 다. <b>같은 문장이 둘 다에 있으면 "대처" 가 이긴다</b> — 보호자에게는 "무엇을 했더니
     * 어떻게 됐다" 가 더 쓸모 있는 정보다.
     *
     * <p>문장을 못 찾으면 건너뛴다. 근거 줄 자체는 그대로 둔다 — 날짜와 기관은 맞고 문장만 없는 것이다.
     */
    private static List<Sentence> sentencesOf(InsightResult insight, SummaryResult summary) {
        JsonNode claims = JsonColumns.readArray(summary.getClaims(), "summary_result.claims", summary.getId());

        // 순서를 지키려고 LinkedHashMap. 값은 역할이고, 대처가 나중에 덮어쓴다.
        Map<Integer, String> roleOf = new LinkedHashMap<>();
        for (JsonNode node : JsonColumns.readArray(insight.getClaimIds(),
                "insight_result.claim_ids", insight.getId())) {
            ClaimRef ref = ClaimRef.parse(node.asText());
            if (ref != ClaimRef.UNKNOWN && ref.summaryId().equals(summary.getId())) {
                roleOf.putIfAbsent(ref.order(), Sentence.SITUATION);
            }
        }
        for (JsonNode support : JsonColumns.readArray(insight.getSupports(),
                "insight_result.supports", insight.getId())) {
            for (JsonNode node : support.path("claim_ids")) {
                ClaimRef ref = ClaimRef.parse(node.asText());
                if (ref != ClaimRef.UNKNOWN && ref.summaryId().equals(summary.getId())) {
                    roleOf.put(ref.order(), Sentence.SUPPORT);  // 둘 다면 대처가 이긴다
                }
            }
        }

        List<Sentence> sentences = new ArrayList<>();
        roleOf.forEach((order, role) -> {
            String text = claims.path(order).path("text").asText("");
            if (!text.isBlank()) {
                sentences.add(new Sentence(text, role));
            }
        });
        return sentences;
    }

    /**
     * 근거(대처 근거 포함)에 교사가 Gate 1 에서 고치거나 새로 쓴 문장({@code EDITED}·{@code TEACHER})이 있는지 (#323).
     * AI 출력의 {@code teacher_claim_ids} 와 같은 판단이다 (CRITERIA §5). 그 값은 저장하지 않아서 요약의
     * {@code source} 로 다시 본다. {@code source} 가 없으면 AI 원문이다 ({@code InsightService.sourceOf}).
     *
     * <p>근거 번호가 없거나 가리키는 요약·문장을 찾지 못하면 <b>있다</b> 로 본다. 확인하지 못한 문장을 AI 원문으로
     * 두면 경고가 사라진다.
     */
    private static boolean hasTeacherClaim(InsightResult insight, Map<Long, SummaryResult> summaries) {
        List<ClaimRef> refs = allClaimRefs(insight);
        if (refs.isEmpty()) {
            return true;
        }
        for (ClaimRef ref : refs) {
            SummaryResult summary = summaries.get(ref.summaryId());
            if (summary == null || !summary.getChildId().equals(insight.getChildId())) {
                return true;
            }
            JsonNode claim = JsonColumns.readArray(summary.getClaims(), "summary_result.claims", summary.getId())
                    .path(ref.order());
            JsonNode source = claim.path("source");
            boolean ai = source.isMissingNode() || source.isNull() || "AI".equals(source.asText());
            if (claim.isMissingNode() || !ai) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code claim_ids} 와 {@code supports[].claim_ids}. 형식이 어긋난 번호는 "확인 못 함" 으로 남긴다.
     * 근거 번호가 없는 대처도 마찬가지다 — 그 대처가 무엇을 근거로 했는지 알 수 없다.
     */
    private static List<ClaimRef> allClaimRefs(InsightResult insight) {
        List<ClaimRef> refs = new ArrayList<>();
        JsonColumns.readArray(insight.getClaimIds(), "insight_result.claim_ids", insight.getId())
                .forEach(node -> refs.add(ClaimRef.parse(node.asText())));
        for (JsonNode support : JsonColumns.readArray(insight.getSupports(),
                "insight_result.supports", insight.getId())) {
            JsonNode claimIds = support.path("claim_ids");
            if (!claimIds.isArray() || claimIds.isEmpty()) {
                refs.add(ClaimRef.UNKNOWN);
                continue;
            }
            claimIds.forEach(node -> refs.add(ClaimRef.parse(node.asText())));
        }
        return refs;
    }

    /** {@code ["31-0", "31-2", "34-1"]} → {@code [31, 34]}. 순서를 지키고 중복은 한 번만 센다. */
    private static List<Long> summaryIdsIn(InsightResult insight) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : JsonColumns.readArray(insight.getClaimIds(),
                "insight_result.claim_ids", insight.getId())) {
            if (!node.isTextual()) {
                continue;
            }
            ClaimRef ref = ClaimRef.parse(node.asText());
            if (ref == ClaimRef.UNKNOWN) {
                log.warn("unexpected claim_id format insightId={} claimId={}", insight.getId(), node.asText());
            } else if (!ids.contains(ref.summaryId())) {
                ids.add(ref.summaryId());
            }
        }
        return ids;
    }

    /**
     * {@code looked_up_claim_ids} 가 가리키는 요약 중 <b>근거로 실제 인용된 것만</b> (#387). AI 계약상 찾아 쓴 claim 은
     * {@code claim_ids}·{@code supports} 안에 들어 있다. 거기 없는 번호는 인용하지 않은 기록이라 근거 목록에 올리지 않는다.
     * 이 컬럼 전에 만든 행은 비어 있다.
     */
    private static Set<Long> lookedUpSummaryIdsOf(InsightResult insight) {
        Set<Long> cited = allClaimRefs(insight).stream().map(ClaimRef::summaryId).collect(Collectors.toSet());
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode node : JsonColumns.readArray(insight.getLookedUpClaimIds(), "insight_result.looked_up_claim_ids",
                insight.getId())) {
            ClaimRef ref = ClaimRef.parse(node.asText());
            if (ref == ClaimRef.UNKNOWN || !cited.contains(ref.summaryId())) {
                log.warn("looked_up_claim_id not cited, ignored insightId={} claimId={}", insight.getId(), node);
                continue;
            }
            ids.add(ref.summaryId());
        }
        return ids;
    }

    /** {@code "31-2"} → 요약 31 의 claims[2]. 읽을 수 없으면 {@link #UNKNOWN} — 어떤 요약도 가리키지 않는다. */
    private record ClaimRef(Long summaryId, int order) {

        static final ClaimRef UNKNOWN = new ClaimRef(-1L, -1);

        static ClaimRef parse(String claimId) {
            int dash = claimId.indexOf('-');
            if (dash <= 0) {
                return UNKNOWN;
            }
            try {
                return new ClaimRef(Long.valueOf(claimId.substring(0, dash)),
                        Integer.parseInt(claimId.substring(dash + 1)));
            } catch (NumberFormatException e) {
                return UNKNOWN;
            }
        }
    }

    /**
     * 근거 한 줄. 인용 순서대로 오고, 같은 요약은 한 번만 온다.
     *
     * @param childContextId 승인본 id. 보호자 일지(G-21)의 id 와 같다
     * @param institutionId  그 요약을 쓴 기관. 기관 행이 남아 있는지는 보지 않았다
     * @param lookedUp       이번 기간이 아니라 AI 가 지난 기록에서 찾아 인용한 근거인지 (#387)
     */
    public record Ref(Long childContextId, LocalDate date, Long institutionId, boolean lookedUp,
            List<Sentence> sentences) {
    }

    /**
     * 인사이트가 실제로 인용한 요약 문장 하나.
     *
     * <p><b>원본 일지 인용이 아니라 Gate 1 에서 교사가 승인한 요약 문장이다.</b> 원본 인용
     * ({@code evidence.quote})에는 다른 아이 이름이 남아 있어 밖으로 내보내지 않는다
     * ({@code AI/insight/CRITERIA.md §2}). 요약 문장은 그 검사를 거쳤다.
     *
     * <p>{@code summary_result.claims} 를 읽는다 — 교사가 <b>고쳐 승인한</b> 문장이다
     * ({@code approveWithClaims}). AI 원문({@code content})이 아니다.
     *
     * @param role {@code "상황"} = 인사이트 본문 근거({@code claim_ids}),
     *             {@code "대처"} = 대처 근거({@code supports[].claim_ids}). 둘 다면 "대처" 다
     */
    public record Sentence(String text, String role) {

        public static final String SITUATION = "상황";
        public static final String SUPPORT = "대처";
    }

    /** {@link #EMPTY} 는 근거 번호가 하나도 없는 인사이트다 — 확인할 수 없으니 교사 작성으로 본다. */
    public record Evidence(List<Ref> refs, boolean teacherWritten) {

        public static final Evidence EMPTY = new Evidence(List.of(), true);

        public Set<Long> institutionIds() {
            return refs.stream().map(Ref::institutionId).collect(Collectors.toSet());
        }
    }
}
