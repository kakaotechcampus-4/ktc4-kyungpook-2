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

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.insight.Gate2InsightResponse;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.EvidenceRef;
import com.itda.backend.dto.response.insight.Gate2InsightResponse.Target;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightKey;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightEvidenceReader.Evidence;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

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
 * <p>근거는 {@link InsightEvidenceReader} 가 읽는다 — 보호자 리포트(G-35 · G-36)가 받는 근거와 같은 범위다 (#405).
 */
@Service
@RequiredArgsConstructor
public class InsightGate2QueryService {

    /** 근거 한 건의 화면 문구. 아직 승인 요약 한 종류뿐이라 고정값이다. */
    private static final String EVIDENCE_LABEL = "관찰일지 요약";

    /**
     * 모든 인사이트가 {@code true} 다. 본문에 <b>다른 아이 이름이 없다는 보장이 없다</b>
     * ({@code AI/insight/CRITERIA.md} §5). 걸러지는 지점이 없어서 모든 글이 "봐야 할 글" 이다.
     * 교사가 쓴 문장이 섞였는지는 건별로 따로 알린다 ({@code teacherWritten}, #323).
     */
    private static final boolean NEEDS_REVIEW = true;

    private final UserService userService;
    private final InsightResultRepository insightResultRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;
    private final InsightEvidenceReader insightEvidenceReader;

    /** 우리 기관이 승인 주체인 인사이트를 최신 것부터. */
    @Transactional(readOnly = true)
    public List<Gate2InsightResponse> getInsights(String userId, String gate2Status) {
        InsightStatus status = toInsightStatus(gate2Status);
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<Long> childIds = rosterOf(organizationId);
        if (childIds.isEmpty()) {
            return List.of();
        }
        // 마지막 회차를 고른 뒤 상태를 건다 — 최신 회차가 이미 승인됐으면 옛 회차의 대기 행이 최신으로 보인다.
        // 회차 고르기와 본문 읽기는 한 SQL 이다 — 나눠 읽으면 그 사이 커밋된 승인이 대기 목록에 섞인다.
        return assemble(organizationId, insightResultRepository.findLatestByChildIdInAndStatus(childIds, status), false);
    }

    /**
     * 대시보드 "분석 승인" 칸. {@link #getInsights} 의 건수와 같다 — 같은 회차 고르기 · 같은 {@link #authorize} 를 지난다.
     * 대시보드가 10초마다 부르므로 판정에 쓰는 칸만 읽고, 본문 · 근거는 읽지 않는다.
     */
    @Transactional(readOnly = true)
    public int countInsights(Long organizationId, InsightStatus status) {
        List<Long> childIds = rosterOf(organizationId);
        if (childIds.isEmpty()) {
            return 0;
        }
        List<InsightKey> keys = insightResultRepository.findLatestKeysByChildIdInAndStatus(childIds, status);
        if (keys.isEmpty()) {
            return 0;
        }
        Map<Long, List<Long>> sourceIds = new HashMap<>();
        keys.forEach(k -> sourceIds.put(k.id(), PrimarySource.sourceIdsOf(k.id(), k.sourceInstitutionIds())));
        // 승인 주체는 근거 기관 중에서만 고르므로, 근거 기관만 읽어도 목록과 같은 답이 나온다.
        Set<Long> organizationIds = new HashSet<>();
        sourceIds.values().forEach(organizationIds::addAll);
        Judgment judgment = judgmentOf(keys.stream().map(InsightKey::childId).collect(Collectors.toSet()),
                organizationIds);
        int count = 0;
        for (InsightKey key : keys) {
            try {
                authorize(key.id(), key.childId(), sourceIds.get(key.id()), organizationId, judgment);
                count++;
            } catch (InsightException e) {
                // 목록에서 조용히 빠지는 글이다
            }
        }
        return count;
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
     * 우리 명부의 아이. 읽을 범위를 좁히는 것일 뿐 울타리가 아니다 — 남의 아이를 막는 것은 {@link #requireStillOurs} 다.
     * 여기가 느슨해져도 결과는 같고, 대신 읽을 행이 늘어난다.
     */
    private List<Long> rosterOf(Long organizationId) {
        return childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).distinct().toList();
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
        Map<Long, Evidence> evidences = insightEvidenceReader.read(insights);

        // 근거 기관 · 명부 기관 · 근거 줄이 가리키는 기관을 한 번에 읽는다. 근거 줄의 기관을 빼면 그 줄이 응답에서 빠진다.
        Set<Long> organizationIds = new HashSet<>();
        insights.forEach(i -> organizationIds.addAll(PrimarySource.sourceIdsOf(i)));
        evidences.values().forEach(e -> organizationIds.addAll(e.institutionIds()));
        Judgment judgment = judgmentOf(childIds, organizationIds);

        List<Gate2InsightResponse> responses = new ArrayList<>();
        for (InsightResult insight : insights) {
            Organization primary;
            try {
                primary = authorize(insight.getId(), insight.getChildId(), PrimarySource.sourceIdsOf(insight),
                        organizationId, judgment);
            } catch (InsightException e) {
                if (strict) {
                    throw e;
                }
                continue;
            }
            responses.add(toResponse(insight, primary, judgment.childNames(), judgment.enrolled(),
                    judgment.consents(), evidences, judgment.organizations()));
        }
        return responses;
    }

    /**
     * 인가 판정에 쓰는 값. 목록 · 단건 · 건수가 같은 방법으로 만든다.
     *
     * @param childNames    지금 살아 있는 아이 이름 — 목록에서 뺀 아이는 이름도 인사이트도 더 보이지 않는다
     *                      (인사이트 재료를 모을 때와 같은 기준, {@code InsightService.prepare})
     * @param enrolled      아이마다 지금 다니는 기관
     * @param consents      주간 인사이트 동의 (#257). 수신 후보 표시와 재료 확인이 같은 값을 본다
     * @param organizations {@code organizationIds} 와 명부 기관
     */
    private record Judgment(Map<Long, String> childNames, Map<Long, List<Long>> enrolled,
            Map<Long, Map<Long, ConsentState>> consents, Map<Long, Organization> organizations) {
    }

    private Judgment judgmentOf(Set<Long> childIds, Set<Long> organizationIds) {
        Map<Long, String> childNames = childRepository.findAllById(childIds).stream()
                .filter(child -> child.getDeletedAt() == null)
                .collect(Collectors.toMap(Child::getId, Child::getName));
        Map<Long, List<Long>> enrolled = childOrganizationRepository.findOrganizationIdsByChildIdIn(childIds);
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.insightStatesOf(childIds);
        Set<Long> toRead = new HashSet<>(organizationIds);
        enrolled.values().forEach(toRead::addAll);
        Map<Long, Organization> organizations = organizationRepository.findAllById(toRead).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));
        return new Judgment(childNames, enrolled, consents, organizations);
    }

    /**
     * 우리 기관이 이 인사이트를 보고 승인할 수 있는지. 승인 주체를 돌려주고, 아니면 {@link InsightException} — 단건은 그대로
     * 응답(404 · 403)이 되고 목록 · 건수는 그 글을 뺀다.
     */
    private static Organization authorize(Long insightId, Long childId, List<Long> sourceIds, Long organizationId,
            Judgment judgment) {
        // 명부를 먼저 본다. source_institution_ids 는 만들 때 굳은 값이라 그 뒤에 아이가 우리
        // 기관을 떠났어도 그대로 남는다 — 그것만 보면 연결이 끊긴 기관이 계속 읽는다.
        requireStillOurs(insightId, childId, organizationId, judgment.childNames(), judgment.enrolled());
        requireMaterialConsented(insightId, childId, sourceIds, judgment.consents());
        return PrimarySource.requireApprover(insightId, sourceIds, organizationId, judgment.organizations());
    }

    /**
     * 재료를 낸 기관 중 하나라도 주간 인사이트 동의가 꺼졌으면 <b>"없음"</b> 으로 답한다 (#257, 멘토님 리뷰 #256 P1).
     *
     * <p>승인 전·후 모두다. 승인 버튼만 409 로 막으면, 승인하는 기관은 목록·상세로 철회된 기관의 내용이 섞인 글을
     * 계속 읽는다. 여러 기관 기록을 엮은 글이라 일부만 가릴 수 없어 통째로 뺀다. 동의 판단은 재료 고르기 · 승인과 같은
     * {@code ConsentStateReader.insightStatesOf} 다 — 다만 재료 고르기와 달리 <b>명부 소속은 보지 않는다</b> (이 PR 전과 같다).
     *
     * <p>숨긴 대기 글은 상태를 바꾸지 않는다. 아무에게도 나가지 않으니 대기로 남아도 위험이 없고, 보호자가 동의를 다시
     * 켜면 그대로 다시 보여 승인할 수 있다. 상태를 바꾸려면 값을 더해야 해서 §11.4 수동 조치 대상이 된다 (#213 과 같은
     * 이유). 그래서 id 로 직접 결정(O-42)을 보내면 승인은 409 {@code INSIGHT_MATERIAL_CONSENT_REVOKED}, 보류는 된다.
     */
    private static void requireMaterialConsented(Long insightId, Long childId, List<Long> sourceIds,
            Map<Long, Map<Long, ConsentState>> consents) {
        Map<Long, ConsentState> ofChild = consents.getOrDefault(childId, Map.of());
        for (Long organizationId : sourceIds) {
            if (ofChild.getOrDefault(organizationId, ConsentState.NOT_GRANTED) != ConsentState.GRANTED) {
                throw new InsightException(InsightErrorCode.NOT_FOUND, insightId);
            }
        }
    }

    /**
     * 지금도 우리가 돌보는 아이의 인사이트인지. 목록에서 뺀 아이이거나 그 아이가 우리 기관을 떠났으면
     * <b>"없음"</b> 으로 답한다 — 연결이 끊긴 뒤에도 본문·아이 이름·그 아이가 지금 다니는 기관 목록이
     * 보이면 안 된다 (CLAUDE.md: 동의가 사라지면 막는다).
     */
    private static void requireStillOurs(Long insightId, Long childId, Long organizationId,
            Map<Long, String> liveChildNames, Map<Long, List<Long>> enrolled) {
        if (!liveChildNames.containsKey(childId)
                || !enrolled.getOrDefault(childId, List.of()).contains(organizationId)) {
            throw new InsightException(InsightErrorCode.NOT_FOUND, insightId);
        }
    }

    private Gate2InsightResponse toResponse(InsightResult insight, Organization primary,
            Map<Long, String> childNames, Map<Long, List<Long>> enrolled,
            Map<Long, Map<Long, ConsentState>> consents, Map<Long, Evidence> evidences,
            Map<Long, Organization> organizations) {
        Long childId = insight.getChildId();
        Map<Long, ConsentState> consentOfChild = consents.getOrDefault(childId, Map.of());
        // 승인하면 실제로 받는 기관과 같은 규칙이다 (Gate2Recipients). 동의가 GRANTED 인 곳만 받는다.
        List<Target> targets = Gate2Recipients.candidatesOf(enrolled.getOrDefault(childId, List.of()), primary.getId())
                .stream()
                .map(organizations::get)
                .filter(org -> org != null)
                .map(org -> new Target(InstitutionResponse.from(org),
                        consentOfChild.getOrDefault(org.getId(), ConsentState.NOT_GRANTED)))
                .toList();

        List<EvidenceRef> evidence = evidences.getOrDefault(insight.getId(), Evidence.EMPTY).refs().stream()
                // 기관 행이 사라진 근거는 뺀다. 이름을 모르는 출처를 화면에 올리느니 그 줄을 안 보이는 쪽이 낫다.
                .filter(ref -> organizations.containsKey(ref.institutionId()))
                .map(ref -> new EvidenceRef(String.valueOf(ref.childContextId()), ref.date().toString(),
                        EVIDENCE_LABEL, InstitutionResponse.from(organizations.get(ref.institutionId())),
                        ref.lookedUp()))
                .toList();

        return new Gate2InsightResponse(
                String.valueOf(insight.getId()),
                String.valueOf(childId),
                childNames.get(childId),
                periodLabel(insight.getPeriodFrom()),
                insight.getContent(),
                NEEDS_REVIEW,
                evidences.getOrDefault(insight.getId(), Evidence.EMPTY).teacherWritten(),
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
    static String periodLabel(LocalDate periodFrom) {
        int weekOfMonth = (periodFrom.getDayOfMonth() - 1) / 7 + 1;
        return "%d년 %d월 %d주차".formatted(periodFrom.getYear(), periodFrom.getMonthValue(), weekOfMonth);
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
