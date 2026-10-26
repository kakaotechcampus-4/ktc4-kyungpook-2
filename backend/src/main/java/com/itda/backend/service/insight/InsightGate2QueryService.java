package com.itda.backend.service.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.EvidenceRef;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.Target;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gate 2 검토 목록과 단건 (api-spec O-40 · O-41). 교사가 발송을 승인하기 전에 봐야 할 것을 모아 준다.
 *
 * <p><b>승인 주체인 기관에만 보인다.</b> 승인할 수 없는 인사이트를 목록에 띄우면 교사가 누를 수 없는 버튼을
 * 보게 되고, 근거를 내지 않은 기관에는 남의 아이 인사이트가 아예 보이면 안 된다 ({@link PrimarySource}).
 *
 * <p><b>최신 회차만 보인다.</b> 워커는 재료가 늘면 같은 아동·기간을 {@code revision + 1} 로 다시 만들고
 * 옛 회차 행을 그대로 둔다 (§9.3). 거르지 않으면 같은 주의 인사이트가 둘 보이고, 철 지난 쪽을 승인하면
 * 낡은 글이 다른 기관으로 나간다.
 *
 * <p>AI 가 쓴 JSON 이 깨져 있어도 한 건 때문에 목록 전체가 500 나지 않게 빈 값으로 읽는다
 * ({@code SummaryGate1QueryService} 와 같은 방식이다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightGate2QueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 근거 한 건의 화면 문구. 아직 승인 요약 한 종류뿐이라 고정값이다. */
    private static final String EVIDENCE_LABEL = "관찰일지 요약";

    /**
     * 지금은 모든 인사이트가 {@code true} 다. 본문에 <b>다른 아이 이름이 없다는 보장이 없고</b>
     * ({@code AI/insight/CRITERIA.md} §5), Gate 1 에서 교사가 검증 없이 고치거나 새로 쓴 문장이 근거로
     * 섞일 수 있다 (#199). 둘 다 걸러지는 지점이 없어서 모든 글이 "봐야 할 글" 이다.
     *
     * <p>AI 계약에 "교사 작성 포함" 표시가 들어오면 (#206 김근찬) 그때 건별로 갈린다. 그 전까지
     * 상수로 두는 것은 화면이 경고를 띄울 자리를 미리 잡아 두기 위해서다 — 나중에 값만 바뀐다.
     */
    private static final boolean NEEDS_REVIEW = true;

    private final UserService userService;
    private final InsightResultRepository insightResultRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;
    private final SummaryResultRepository summaryResultRepository;
    private final ChildContextRepository childContextRepository;

    /** 우리 기관이 승인 주체인 인사이트를 최신 것부터. */
    @Transactional(readOnly = true)
    public List<Gate2InsightResponse> getInsights(String userId, String gate2Status) {
        InsightStatus status = toInsightStatus(gate2Status);
        Long organizationId = userService.getOrganizationIdOf(userId);

        // 읽을 범위를 좁히는 것일 뿐 울타리가 아니다 — 남의 아이를 막는 것은 requireStillOurs 다.
        // 여기가 느슨해져도 결과는 같고, 대신 읽을 행이 늘어난다.
        List<Long> childIds = childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).distinct().toList();
        if (childIds.isEmpty()) {
            return List.of();
        }

        // 상태로 먼저 좁히지 않는다 — 최신 회차가 이미 승인됐으면 옛 회차의 대기 행이 최신으로 보인다.
        List<InsightResult> latest = latestRevisionOnly(insightResultRepository.findByChildIdInOrderByIdDesc(childIds));
        List<InsightResult> mine = latest.stream().filter(i -> i.getStatus() == status).toList();
        return assemble(organizationId, mine, false);
    }

    /**
     * 단건. 인가 규칙은 목록과 같다 — 지금 우리 명부에 있는 아이여야 하고, 우리가 승인 주체여야 한다.
     * 옛 회차도 id 로 직접 물으면 보여준다. 승인만 막는다.
     */
    @Transactional(readOnly = true)
    public Gate2InsightResponse getInsight(String userId, Long insightId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        InsightResult insight = insightResultRepository.findById(insightId)
                .orElseThrow(() -> new InsightException(InsightErrorCode.NOT_FOUND, insightId));
        return assemble(organizationId, List.of(insight), true).get(0);
    }

    /**
     * 같은 아동·기간의 마지막 회차만 남긴다. 목록이 id 내림차순이라 같은 묶음에서 처음 본 것이 최신이지만,
     * 회차 번호로 가른다 — id 순서가 회차 순서와 어긋날 여지를 남기지 않는다.
     */
    private static List<InsightResult> latestRevisionOnly(List<InsightResult> insights) {
        Map<PeriodKey, Integer> maxRevision = new HashMap<>();
        for (InsightResult insight : insights) {
            maxRevision.merge(PeriodKey.of(insight), insight.getRevision(), Math::max);
        }
        return insights.stream()
                .filter(i -> i.getRevision().equals(maxRevision.get(PeriodKey.of(i))))
                .toList();
    }

    private record PeriodKey(Long childId, LocalDate periodFrom, LocalDate periodTo) {

        static PeriodKey of(InsightResult insight) {
            return new PeriodKey(insight.getChildId(), insight.getPeriodFrom(), insight.getPeriodTo());
        }
    }

    /**
     * 인사이트들을 화면 모양으로 만든다. 인사이트 수와 상관없이 쿼리 수가 같도록 필요한 것을 한 번에 읽는다.
     *
     * @param strict 단건 조회처럼 <b>그 한 건의 인가 실패를 응답으로 알려야</b> 하는지. 목록은 승인 주체가
     *               아닌 인사이트를 조용히 빼기만 한다
     */
    private List<Gate2InsightResponse> assemble(Long organizationId, List<InsightResult> insights, boolean strict) {
        if (insights.isEmpty()) {
            return List.of();
        }
        Set<Long> childIds = insights.stream().map(InsightResult::getChildId).collect(Collectors.toSet());
        // 목록에서 뺀 아이는 이름도 인사이트도 더 보이지 않는다 — 인사이트 재료를 모을 때와 같은 기준이다
        // (InsightService.prepare).
        Map<Long, String> childNames = childRepository.findAllById(childIds).stream()
                .filter(child -> child.getDeletedAt() == null)
                .collect(Collectors.toMap(Child::getId, Child::getName));

        Map<Long, List<Long>> enrolled = childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(childIds)
                .stream().collect(Collectors.groupingBy(ChildOrganization::getChildId,
                        Collectors.mapping(ChildOrganization::getOrganizationId, Collectors.toList())));
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(childIds);
        Map<Long, Evidence> evidences = evidenceOf(insights);

        Set<Long> organizationIds = new HashSet<>();
        insights.forEach(i -> organizationIds.addAll(PrimarySource.sourceIdsOf(i)));
        enrolled.values().forEach(organizationIds::addAll);
        evidences.values().forEach(e -> organizationIds.addAll(e.institutionIds()));
        Map<Long, Organization> organizations = organizationRepository.findAllById(organizationIds).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));

        List<Gate2InsightResponse> responses = new ArrayList<>();
        for (InsightResult insight : insights) {
            Organization primary;
            try {
                // 명부를 먼저 본다. source_institution_ids 는 만들 때 굳은 값이라 그 뒤에 아이가 우리
                // 기관을 떠났어도 그대로 남는다 — 그것만 보면 연결이 끊긴 기관이 계속 읽는다.
                requireStillOurs(insight, organizationId, childNames, enrolled);
                primary = PrimarySource.requireApprover(insight, organizationId, organizations);
            } catch (InsightException e) {
                if (strict) {
                    throw e;
                }
                continue;
            }
            responses.add(toResponse(insight, primary, childNames, enrolled, consents, evidences, organizations));
        }
        return responses;
    }

    /**
     * 지금도 우리가 돌보는 아이의 인사이트인지. 목록에서 뺀 아이이거나 그 아이가 우리 기관을 떠났으면
     * <b>"없음"</b> 으로 답한다 — 연결이 끊긴 뒤에도 본문·아이 이름·그 아이가 지금 다니는 기관 목록이
     * 보이면 안 된다 (CLAUDE.md: 동의가 사라지면 막는다).
     */
    private static void requireStillOurs(InsightResult insight, Long organizationId,
            Map<Long, String> liveChildNames, Map<Long, List<Long>> enrolled) {
        Long childId = insight.getChildId();
        if (!liveChildNames.containsKey(childId)
                || !enrolled.getOrDefault(childId, List.of()).contains(organizationId)) {
            throw new InsightException(InsightErrorCode.NOT_FOUND, insight.getId());
        }
    }

    private Gate2InsightResponse toResponse(InsightResult insight, Organization primary,
            Map<Long, String> childNames, Map<Long, List<Long>> enrolled,
            Map<Long, Map<Long, ConsentState>> consents, Map<Long, Evidence> evidences,
            Map<Long, Organization> organizations) {
        Long childId = insight.getChildId();
        Map<Long, ConsentState> consentOfChild = consents.getOrDefault(childId, Map.of());
        List<Target> targets = enrolled.getOrDefault(childId, List.of()).stream()
                // 승인하는 기관 자신은 수신 후보가 아니다 — 자기 글을 자기에게 공유하지 않는다.
                .filter(id -> !id.equals(primary.getId()))
                .map(organizations::get)
                .filter(org -> org != null)
                .sorted(java.util.Comparator.comparing(Organization::getId))
                .map(org -> new Target(InstitutionResponse.from(org),
                        consentOfChild.getOrDefault(org.getId(), ConsentState.NOT_GRANTED)))
                .toList();

        List<EvidenceRef> evidence = evidences.getOrDefault(insight.getId(), Evidence.EMPTY).refs().stream()
                // 기관 행이 사라진 근거는 뺀다. 이름을 모르는 출처를 화면에 올리느니 그 줄을 안 보이는 쪽이 낫다.
                .filter(ref -> organizations.containsKey(ref.institutionId()))
                .map(ref -> new EvidenceRef(ref.childContextId(), ref.date(), EVIDENCE_LABEL,
                        InstitutionResponse.from(organizations.get(ref.institutionId()))))
                .toList();

        return new Gate2InsightResponse(
                String.valueOf(insight.getId()),
                String.valueOf(childId),
                childNames.get(childId),
                periodLabel(insight.getPeriodFrom()),
                insight.getContent(),
                NEEDS_REVIEW,
                InstitutionResponse.from(primary),
                evidence,
                targets,
                insight.getStatus() == InsightStatus.GATE2_PENDING
                        ? "pending" : insight.getStatus().name().toLowerCase(Locale.ROOT));
    }

    /**
     * "2026년 8월 3주차". {@code periodFrom} 이 속한 달의 몇째 주인지로 적는다. 한 주가 달을 걸치면
     * {@code periodFrom} 이 있는 달로 간다 — 화면 문구라 달력 규칙보다 한 가지로 읽히는 쪽이 낫다.
     *
     * <p>지금 {@code periodFrom} 은 월요일이다 ({@code app.insight.period-days: 7} + 일요일 마감,
     * {@code InsightWorker}). <b>그 설정을 7 이 아닌 값으로 바꾸면 "주차" 라는 말 자체가 안 맞는다</b> —
     * 기간 단위를 바꾸는 날 이 문구도 같이 정해야 한다.
     */
    private static String periodLabel(LocalDate periodFrom) {
        int weekOfMonth = (periodFrom.getDayOfMonth() - 1) / 7 + 1;
        return "%d년 %d월 %d주차".formatted(periodFrom.getYear(), periodFrom.getMonthValue(), weekOfMonth);
    }

    /**
     * 인사이트별 근거. {@code claim_ids} 는 {@code "{summary_result.id}-{claims 배열 순서}"} 라 앞자리로
     * 요약을 찾고, 그 요약의 승인본({@code child_context})·날짜·기관을 붙인다. 승인본이 없는 요약은 뺀다 —
     * Gate 1 을 통과하지 않은 글은 근거가 아니다.
     */
    private Map<Long, Evidence> evidenceOf(List<InsightResult> insights) {
        Map<Long, List<Long>> summaryIdsOf = new HashMap<>();
        Set<Long> allSummaryIds = new HashSet<>();
        for (InsightResult insight : insights) {
            List<Long> ids = summaryIdsIn(insight);
            summaryIdsOf.put(insight.getId(), ids);
            allSummaryIds.addAll(ids);
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
            List<Ref> refs = new ArrayList<>();
            for (Long summaryId : summaryIdsOf.getOrDefault(insight.getId(), List.of())) {
                SummaryResult summary = summaries.get(summaryId);
                Long contextId = contextIdOf.get(summaryId);
                if (summary == null || contextId == null) {
                    continue;
                }
                if (!summary.getChildId().equals(insight.getChildId())) {
                    // claim_id 는 AI 가 쓴 글자다. 숫자 하나가 어긋나면 다른 아이의 요약을 가리키고, 그
                    // 아이의 기관·날짜·승인본 id 가 아무 상관 없는 교사의 화면에 뜬다. 규칙으로 막는다
                    // (CLAUDE.md: 접근 판단을 LLM 동작에 맡기지 않는다).
                    log.warn("claim_id points at another child's summary, dropped insightId={} summaryId={}",
                            insight.getId(), summaryId);
                    continue;
                }
                refs.add(new Ref(String.valueOf(contextId), summary.getEntryDate().toString(),
                        summary.getInstitutionId()));
            }
            evidences.put(insight.getId(), new Evidence(refs));
        }
        return evidences;
    }

    /** {@code ["31-0", "31-2", "34-1"]} → {@code [31, 34]}. 순서를 지키고 중복은 한 번만 센다. */
    private List<Long> summaryIdsIn(InsightResult insight) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : readArray(insight.getClaimIds(), insight.getId())) {
            if (!node.isTextual()) {
                continue;
            }
            String claimId = node.asText();
            int dash = claimId.indexOf('-');
            if (dash <= 0) {
                continue;
            }
            try {
                Long summaryId = Long.valueOf(claimId.substring(0, dash));
                if (!ids.contains(summaryId)) {
                    ids.add(summaryId);
                }
            } catch (NumberFormatException e) {
                log.warn("unexpected claim_id format insightId={} claimId={}", insight.getId(), claimId);
            }
        }
        return ids;
    }

    private static JsonNode readArray(String raw, Long insightId) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node.isArray() ? node : MAPPER.createArrayNode();
        } catch (JsonProcessingException e) {
            log.warn("failed to parse insight_result.claim_ids id={}", insightId, e);
            return MAPPER.createArrayNode();
        }
    }

    private record Ref(String childContextId, String date, Long institutionId) {
    }

    private record Evidence(List<Ref> refs) {

        static final Evidence EMPTY = new Evidence(List.of());

        Set<Long> institutionIds() {
            return refs.stream().map(Ref::institutionId).collect(Collectors.toSet());
        }
    }

    private static InsightStatus toInsightStatus(String gate2Status) {
        return switch (gate2Status) {
            case "pending" -> InsightStatus.GATE2_PENDING;
            case "approved" -> InsightStatus.APPROVED;
            case "hold" -> InsightStatus.HOLD;
            // 다른 값을 조용히 대기 목록으로 처리하면 화면이 엉뚱한 목록을 보여준다.
            default -> throw new InsightException(InsightErrorCode.INVALID_REQUEST,
                    "unsupported gate2Status: " + gate2Status);
        };
    }
}
