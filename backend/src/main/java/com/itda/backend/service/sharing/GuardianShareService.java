package com.itda.backend.service.sharing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse.ShareTarget;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.insight.PrimarySource;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보호자가 인사이트마다 <b>어느 기관에 열어줄지</b> 고른다 (#223).
 *
 * <p>10/17 회의 결론 {@code 담당자 승인 → 보호자 → 보호자가 기관별 승인} 의 마지막 칸이다.
 * {@code sharing_history} 의 {@code ORGANIZATION} 행을 만드는 <b>유일한 경로</b>라, 이것이 없으면
 * 기관 열람 권한이 생길 길이 없고 수신함(O-50)은 만들어도 빈 화면이다 (§9.4).
 *
 * <p><b>Gate 2 와 달리 되돌릴 수 있다.</b> 교사의 발송 결정은 한 번뿐이지만, 보호자는 자기 아이
 * 정보의 주인이다. 열람 방식을 고른 이유도 "철회하면 그 뒤로 안 보인다" 였다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianShareService {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final SharingConsentRepository sharingConsentRepository;
    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;

    /**
     * 내게 열린 인사이트 전부, 최근 열린 순.
     *
     * <p>ponytail: {@code toResponse} 가 인사이트마다 기관/동의/명부를 다시 읽어 질의가 건수에 비례한다.
     * 보호자 한 명이 아이 한둘, 주마다 한 편이라 지금은 가볍다. 무거워지면 아이별로 모아 한 번만 읽는다.
     */
    @Transactional(readOnly = true)
    public List<GuardianInsightResponse> getInsights(String userId) {
        Long guardianId = userService.getParentIdOf(userId);
        List<SharingHistory> mine = sharingHistoryRepository
                .findByReceiverTypeAndReceiverIdAndRevokedAtIsNullOrderByIdDesc(
                        ReceiverType.GUARDIAN, guardianId);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, InsightResult> insights = insightResultRepository
                .findAllById(mine.stream().map(SharingHistory::getInsightResultId).toList())
                .stream().collect(Collectors.toMap(InsightResult::getId, insight -> insight));

        return mine.stream()
                .map(grant -> toResponse(grant, insights.get(grant.getInsightResultId())))
                .filter(response -> response != null)
                .toList();
    }

    /** 단건. 처음 열어 보면 그 시각을 남긴다 — 열람 방식의 감사 추적이다 (§9.4). */
    @Transactional
    public GuardianInsightResponse getInsight(String userId, Long insightId) {
        Long guardianId = userService.getParentIdOf(userId);
        SharingHistory grant = myGrant(insightId, guardianId);
        grant.markRead(LocalDateTime.now());
        GuardianInsightResponse response = toResponse(grant, insightResultRepository.findById(insightId).orElse(null));
        if (response == null) {
            throw new SharingException(SharingErrorCode.NOT_FOUND, insightId);
        }
        return response;
    }

    /**
     * 공유할 기관을 고른다. <b>고른 목록이 곧 최종 상태다</b> — 여기 없는 기관은 꺼진다.
     *
     * <p>같은 요청을 두 번 보내도 결과가 같다. 화면이 체크박스라 그 모양이 그대로 온다.
     */
    @Transactional
    public GuardianInsightResponse share(String userId, Long insightId, List<Long> institutionIds) {
        Long guardianId = userService.getParentIdOf(userId);
        SharingHistory grant = myGrant(insightId, guardianId);
        InsightResult insight = insightResultRepository.findById(insightId)
                .orElseThrow(() -> new SharingException(SharingErrorCode.NOT_FOUND, insightId));

        // 동의를 읽기 전에 아이 행을 잠근다 — ConsentService 와 같은 순서다 (§3.3). 안 잠그면 철회와
        // 겹칠 때 "동의 확인 → (철회 커밋) → 권한 생성" 이 되어 꺼진 동의에 권한이 남는다.
        childRepository.findByIdForUpdate(insight.getChildId());

        Set<Long> allowed = shareableInstitutionIds(insight);
        Set<Long> wanted = new HashSet<>(institutionIds == null ? List.of() : institutionIds);
        // 고를 수 없는 기관이 섞여 있으면 하나도 반영하지 않는다. 일부만 켜 두면 보호자는 전부
        // 켜졌다고 믿는다 — 기관 경계를 넘는 결정이라 조용히 넘어가면 안 된다.
        if (!allowed.containsAll(wanted)) {
            throw new SharingException(SharingErrorCode.NOT_SHAREABLE,
                    "insightId=" + insightId + " wanted=" + wanted + " allowed=" + allowed);
        }

        LocalDateTime now = LocalDateTime.now();
        Map<Long, SharingHistory> existing = sharingHistoryRepository
                .findByInsightResultIdAndReceiverType(insightId, ReceiverType.ORGANIZATION).stream()
                .collect(Collectors.toMap(SharingHistory::getReceiverId, history -> history));

        wanted.forEach(organizationId -> openTo(insight, grant.getSenderOrganizationId(), organizationId,
                existing.get(organizationId), now));
        // 켜진 행 전부를 보고 끈다. 후보 목록만 돌면, 명부에서 빠져 후보가 아니게 된 기관의 켜진 권한을
        // 보호자가 영영 못 끈다 — 화면에는 보이는데 (toResponse) 체크를 풀어도 아무 일이 없다.
        existing.values().stream()
                .filter(SharingHistory::isActive)
                .filter(row -> !wanted.contains(row.getReceiverId()))
                .forEach(row -> row.revoke(ConsentRevokeReason.GUARDIAN_REVOKED, now));
        log.info("guardian set insight sharing insightId={} guardianId={} institutions={}",
                insightId, guardianId, wanted);
        return toResponse(grant, insight);
    }

    /**
     * 그 기관에 연다. <b>거뒀던 권한이 있으면 같은 행을 되살린다</b> — 새로 넣으면
     * {@code (insight_result_id, receiver_type, receiver_id)} UNIQUE 에 걸린다 (§3.3).
     */
    private void openTo(InsightResult insight, Long senderOrganizationId, Long organizationId,
            SharingHistory existing, LocalDateTime now) {
        if (existing != null) {
            existing.regrant(now);
            return;
        }
        sharingHistoryRepository.save(SharingHistory.grant(insight.getChildId(), insight.getId(),
                senderOrganizationId, ReceiverType.ORGANIZATION, organizationId));
    }

    /**
     * 내게 열린 권한. 없으면 <b>"없음"</b> 으로 답한다 — 남의 아이 인사이트가 있는지조차 알려주지
     * 않는다 (deny-by-default, CLAUDE.md).
     */
    private SharingHistory myGrant(Long insightId, Long guardianId) {
        return sharingHistoryRepository
                .findByInsightResultIdAndReceiverTypeAndReceiverId(insightId, ReceiverType.GUARDIAN, guardianId)
                .filter(SharingHistory::isActive)
                .orElseThrow(() -> new SharingException(SharingErrorCode.NOT_FOUND, insightId));
    }

    /**
     * 보호자가 고를 수 있는 기관.
     *
     * <table>
     * <tr><td>그 아이 명부에 있는 기관</td><td>더 이상 다니지 않는 곳에는 보낼 수 없다</td></tr>
     * <tr><td>동의가 살아 있는 기관</td><td>동의 없이 글이 가면 안 된다 (deny-by-default)</td></tr>
     * <tr><td>재료를 낸 기관은 뺀다</td><td>자기가 낸 기록으로 만든 글을 자기에게 다시 열 필요가 없다</td></tr>
     * </table>
     */
    private Set<Long> shareableInstitutionIds(InsightResult insight) {
        Long childId = insight.getChildId();
        Set<Long> enrolled = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId).stream()
                .map(ChildOrganization::getOrganizationId).collect(Collectors.toSet());
        Set<Long> consented = sharingConsentRepository
                .findByChildIdAndStatus(childId, SharingConsentStatus.ACTIVE).stream()
                .map(SharingConsent::getOrganizationId).collect(Collectors.toSet());
        Set<Long> material = new HashSet<>(PrimarySource.sourceIdsOf(insight));

        return enrolled.stream()
                .filter(consented::contains)
                .filter(id -> !material.contains(id))
                .collect(Collectors.toSet());
    }

    private GuardianInsightResponse toResponse(SharingHistory grant, InsightResult insight) {
        if (insight == null) {
            // 인사이트는 지우지 않는 테이블이라 올 일이 없다. 와도 목록 전체를 500 내지는 않는다.
            log.warn("sharing grant points at a missing insight insightId={}", grant.getInsightResultId());
            return null;
        }
        Set<Long> shared = sharingHistoryRepository
                .findByInsightResultIdAndReceiverType(insight.getId(), ReceiverType.ORGANIZATION).stream()
                .filter(SharingHistory::isActive)
                .map(SharingHistory::getReceiverId)
                .collect(Collectors.toSet());

        // 이미 켜 둔 기관은 후보에서 빠졌더라도 보여준다 — 명부에서 빠지면 후보가 아니게 되는데,
        // 목록에서 사라지면 보호자는 아직 켜져 있다는 걸 모르고 끌 수도 없다.
        Set<Long> targetIds = new HashSet<>(shareableInstitutionIds(insight));
        targetIds.addAll(shared);

        Set<Long> toLoad = new HashSet<>(targetIds);
        toLoad.add(grant.getSenderOrganizationId());
        Map<Long, Organization> organizations = organizationRepository.findAllById(toLoad).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));

        List<ShareTarget> targets = new ArrayList<>();
        targetIds.stream().sorted().forEach(id -> {
            Organization organization = organizations.get(id);
            if (organization != null) {
                targets.add(new ShareTarget(InstitutionResponse.from(organization), shared.contains(id)));
            }
        });

        Organization sender = organizations.get(grant.getSenderOrganizationId());
        String childName = childRepository.findByIdAndDeletedAtIsNull(insight.getChildId())
                .map(Child::getName).orElse(null);

        return new GuardianInsightResponse(
                String.valueOf(insight.getId()),
                String.valueOf(insight.getChildId()),
                childName,
                periodLabel(insight.getPeriodFrom()),
                insight.getContent(),
                sender == null ? null : InstitutionResponse.from(sender),
                targets,
                grant.getReadAt());
    }

    /** Gate 2 목록과 같은 문구 규칙이다 ({@code InsightGate2QueryService.periodLabel}). */
    private static String periodLabel(LocalDate periodFrom) {
        int weekOfMonth = (periodFrom.getDayOfMonth() - 1) / 7 + 1;
        return "%d년 %d월 %d주차".formatted(periodFrom.getYear(), periodFrom.getMonthValue(), weekOfMonth);
    }
}
