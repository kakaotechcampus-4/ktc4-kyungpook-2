package com.itda.backend.service.insight;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.Gate2Action;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.insight.Gate2DecisionResponse;
import com.itda.backend.exception.insight.InsightErrorCode;
import com.itda.backend.exception.insight.InsightException;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gate 2 — 교사가 인사이트를 발송 전에 승인·보류한다 (DB 스키마 §9.1, §9.3, §9.4).
 *
 * <p><b>기관 경계를 넘는 마지막 관문이다.</b> 여기를 지나면 글이 다른 사람에게 보인다. 그래서
 * 되돌리는 경로를 두지 않고(승인 취소 없음), 결정마다 {@code human_review} 에 한 줄 남긴다.
 *
 * <p><b>승인하면 보호자와 동의한 기관에 함께 열람 권한이 생긴다</b> (#233). 보호자가 미리 해 둔 기관별
 * 공유 동의를 그대로 쓴다 — 글마다 기관을 고르는 단계는 10/26 기획 변경으로 없앴다. 받는 기관 규칙은
 * {@link Gate2Recipients} 와 {@code consentedRecipients} 에 있다. 보내는 행위가 없으므로 전송 실패도 없다.
 *
 * <p>동시에 두 결정이 와도 한쪽만 반영되도록 인사이트 행을 잠그고 상태를 본다. 열람 권한은
 * {@code (insight_result_id, receiver_type, receiver_id)} UNIQUE 가 마지막으로 한 번 더 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightGate2Service {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final InsightResultRepository insightResultRepository;
    private final OrganizationRepository organizationRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final ApprovalRepository approvalRepository;
    private final ConsentStateReader consentStateReader;

    @Transactional
    public Gate2DecisionResponse decide(String userId, Long insightId, Gate2Action action) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        InsightResult insight = insightResultRepository.findByIdForUpdate(insightId)
                .orElseThrow(() -> new InsightException(InsightErrorCode.NOT_FOUND, insightId));

        requireStillOurs(insight, organizationId);
        Organization primary = PrimarySource.requireApprover(insight, organizationId, organizationsOf(insight));
        requireDecidable(insight);
        requireLatestRevision(insight);

        ApprovalDecision decision = switch (action) {
            case APPROVE -> {
                // 동의를 읽기 전에 아이 행을 잠근다 — ConsentService 와 같은 순서다 (§3.3). 안 잠그면 철회와
                // 겹칠 때 "동의 확인 → (철회 커밋) → 권한 생성" 이 되어, 꺼진 동의에 권한이 남는다.
                childRepository.findByIdForUpdate(insight.getChildId());
                // 잠근 뒤 한 번만 읽는다. 재료 확인과 받는 기관이 같은 스냅샷을 본다.
                Map<Long, ConsentState> insightConsents = consentStateReader.insightStatesOf(
                        List.of(insight.getChildId())).getOrDefault(insight.getChildId(), Map.of());
                requireMaterialStillConsented(insight, insightConsents);
                yield approve(insight, primary, insightConsents);
            }
            case HOLD -> hold(insight);
        };
        approvalRepository.save(Approval.gate2(insightId, Long.valueOf(userId), decision));
        return new Gate2DecisionResponse(String.valueOf(insightId),
                insight.getStatus().name().toLowerCase(Locale.ROOT), decision.name());
    }

    /**
     * 지금도 우리가 돌보는 아이인지. {@code source_institution_ids} 는 인사이트를 만들 때 굳은 값이라
     * 그 뒤에 아이가 우리 기관을 떠나도 그대로 남는다 — 그것만 보면 <b>연결이 끊긴 기관이 계속 승인해서
     * 보호자에게 글을 열어 준다.</b> 조회와 같은 기준으로 먼저 막는다 (CLAUDE.md: 동의가 사라지면 막는다).
     */
    private void requireStillOurs(InsightResult insight, Long organizationId) {
        if (childRepository.findByIdAndDeletedAtIsNull(insight.getChildId()).isEmpty()
                || !childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(
                        insight.getChildId(), organizationId)) {
            throw new InsightException(InsightErrorCode.NOT_FOUND, insight.getId());
        }
    }

    private Map<Long, Organization> organizationsOf(InsightResult insight) {
        return organizationRepository.findAllById(PrimarySource.sourceIdsOf(insight)).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));
    }

    /**
     * 승인한다. 그 아이에 연결된 보호자마다 열람 권한 한 줄을 만든다.
     *
     * <p><b>보호자가 한 명도 없으면 지금은 아무도 못 본다.</b> 오류가 아니라 정상이다 — 초대가 아직 수락되지 않은
     * 아이가 있다. 다만 교사는 "보냈다" 고 믿으므로 로그를 남긴다 (CLAUDE.md Auditability). 나중에 연결되는 보호자는
     * 초대를 수락할 때 {@link InsightAccessGranter} 가 연다 (#287).
     */
    private ApprovalDecision approve(InsightResult insight, Organization primary,
            Map<Long, ConsentState> insightConsents) {
        insight.approveGate2();
        List<ChildGuardian> guardians =
                childGuardianRepository.findByChildIdAndDeletedAtIsNullOrderByLinkedAtAsc(insight.getChildId());
        if (guardians.isEmpty()) {
            log.warn("gate2 approved but no guardian linked, nobody can read it insightId={} childId={}",
                    insight.getId(), insight.getChildId());
        }
        // 승인은 한 번뿐이라(requireDecidable) 같은 수신자에게 두 번 열리지 않는다. 그래서 여기서는
        // 되살리기를 보지 않는다 — 닿지 않는 가지를 두면 되살리기가 되는 줄 착각하게 된다.
        sharingHistoryRepository.saveAll(guardians.stream()
                .map(guardian -> SharingHistory.grant(insight.getChildId(), insight.getId(), primary.getId(),
                        ReceiverType.GUARDIAN, guardian.getUserId()))
                .toList());
        sharingHistoryRepository.saveAll(consentedRecipients(insight, primary, insightConsents).stream()
                .map(organizationId -> SharingHistory.grant(insight.getChildId(), insight.getId(),
                        primary.getId(), ReceiverType.ORGANIZATION, organizationId))
                .toList());
        return ApprovalDecision.APPROVED;
    }

    /**
     * 승인하는 순간 동의가 켜져 있는 받을 기관 (#233). 보호자가 미리 해 둔 기관별 동의를 그대로 쓴다 —
     * 글마다 고르는 단계는 10/26 기획 변경으로 없앴다.
     *
     * <p><b>승인 뒤에 동의한 기관은 이 글을 받지 않는다.</b> 소급하지 않는다 — 동의는 그 뒤에 나온 글부터다.
     */
    private List<Long> consentedRecipients(InsightResult insight, Organization primary,
            Map<Long, ConsentState> insightConsents) {
        Long childId = insight.getChildId();
        List<Long> enrolled = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId).stream()
                .map(ChildOrganization::getOrganizationId)
                .toList();
        return Gate2Recipients.candidatesOf(enrolled, primary.getId()).stream()
                // 하루 요약만 허락한 기관은 받지 않는다 (#257, 멘토님 리뷰 #256 P1).
                .filter(id -> insightConsents.getOrDefault(id, ConsentState.NOT_GRANTED) == ConsentState.GRANTED)
                .toList();
    }

    private ApprovalDecision hold(InsightResult insight) {
        insight.holdGate2();
        return ApprovalDecision.HOLD;
    }

    /**
     * 재료가 된 기관의 공유 동의가 지금도 살아 있는지. <b>승인에만 본다</b> — 보류는 아무 데도 안 나가므로
     * 막을 이유가 없고, 막으면 교사가 철회된 인사이트를 치울 길이 사라진다.
     *
     * <p>인사이트를 만들 때는 동의가 있었다 ({@code InsightService.consentedInstitutionsOf}). 그 뒤 교사가
     * 승인을 누르기까지 며칠이 걸리고, 그사이 보호자가 철회할 수 있다. 여기서 다시 보지 않으면
     * <b>철회된 기관의 기록이 섞인 글이 그대로 나간다</b> (CLAUDE.md: 동의가 사라지면 막는다).
     *
     * <p>인사이트는 여러 기관 claim 을 엮은 결과라 문장 일부만 뺄 수 없다. 하나라도 꺼져 있으면 통째로
     * 막는다 ({@code AI/insight/CRITERIA.md} §7).
     */
    private void requireMaterialStillConsented(InsightResult insight, Map<Long, ConsentState> insightConsents) {
        for (Long organizationId : PrimarySource.sourceIdsOf(insight)) {
            // 재료를 고를 때와 같은 기준이다 — 주간 인사이트를 범위에서 뺀 것도 철회로 본다 (#257).
            if (insightConsents.getOrDefault(organizationId, ConsentState.NOT_GRANTED) != ConsentState.GRANTED) {
                throw new InsightException(InsightErrorCode.MATERIAL_CONSENT_REVOKED,
                        "insightId=" + insight.getId() + " organizationId=" + organizationId);
            }
        }
    }

    /** 승인도 보류도 한 번뿐이다. 되돌리는 경로가 없어 두 번째 결정은 받을 수 없다. */
    private static void requireDecidable(InsightResult insight) {
        if (insight.getStatus() != InsightStatus.GATE2_PENDING) {
            throw new InsightException(InsightErrorCode.ALREADY_REVIEWED, insight.getId());
        }
    }

    /**
     * 같은 아동·기간의 마지막 회차만 승인한다. 재료가 늘면 워커가 {@code revision + 1} 로 다시 만들고 옛
     * 회차를 그대로 두는데(§9.3), 그걸 승인하면 <b>철 지난 글이 다른 기관으로 나간다.</b> 목록에는 최신
     * 회차만 보이지만 id 를 직접 보내면 닿으므로 여기서도 본다.
     *
     * <p>ponytail: <b>이 검사와 워커 사이에는 좁은 틈이 있다.</b> 우리가 잠근 것은 옛 회차의 <i>행</i>인데
     * 워커는 새 <i>행</i>을 넣으므로 그 잠금이 막지 못한다 — 여기를 통과한 직후 워커가 다음 회차를 커밋하면
     * 옛 회차가 승인된 채로 남는다. 교사가 화면을 보고 누르는 속도(초)와 워커 주기(분)의 차이라 MVP 에서는
     * 받아들인다. 닫아야 하면 두 쪽이 함께 잠그는 자리({@code insight_run} 의 그 아동·기간 행)가 필요하다.
     */
    private void requireLatestRevision(InsightResult insight) {
        Integer latest = insightResultRepository.findMaxRevision(
                insight.getChildId(), insight.getPeriodFrom(), insight.getPeriodTo());
        if (latest != null && latest > insight.getRevision()) {
            throw new InsightException(InsightErrorCode.SUPERSEDED, insight.getId());
        }
    }
}
