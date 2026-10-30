package com.itda.backend.service.sharing;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보호자가 받은 인사이트를 읽는다 (#233).
 *
 * <p>Gate 2 승인이 보호자와 동의한 기관에 권한을 함께 연다. 보호자는 여기서 읽기만 한다 — 글마다 기관을
 * 고르는 단계는 10/26 기획 변경으로 없앴다. 특정 기관을 막고 싶으면 그 기관 동의를 끈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianInsightService {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final OrganizationRepository organizationRepository;
    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final SharingReadRecorder sharingReadRecorder;

    /**
     * 내게 열린 인사이트 전부, 최근 열린 순.
     *
     * <p>ponytail: {@code toResponse} 가 인사이트마다 받은 기관을 다시 읽어 질의가 건수에 비례한다.
     * 보호자 한 명이 아이 한둘, 주마다 한 편이라 지금은 가볍다. 무거워지면 인사이트 id 로 모아 한 번만 읽는다.
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

    /**
     * 단건. 처음 열어 보면 그 시각을 남긴다 — 열람 방식의 감사 추적이다 (§9.4). 철회와 겹치면 먼저 반영된 쪽을
     * 따른다 ({@link SharingReadRecorder}). 남의 아이 인사이트는 있는지조차 알려주지 않는다 (deny-by-default).
     */
    @Transactional
    public GuardianInsightResponse getInsight(String userId, Long insightId) {
        Long guardianId = userService.getParentIdOf(userId);
        SharingHistory grant = sharingReadRecorder.lockActive(insightId, ReceiverType.GUARDIAN, guardianId);
        InsightResult insight = insightResultRepository.findById(insightId).orElse(null);
        if (insight == null) {
            // 열람 시각을 남기기 전에 막는다. 못 연 글을 봤다고 기록하지 않는다.
            log.warn("sharing grant points at a missing insight insightId={}", insightId);
            throw new SharingException(SharingErrorCode.NOT_FOUND, insightId);
        }
        return toResponse(sharingReadRecorder.markRead(grant), insight);
    }

    private GuardianInsightResponse toResponse(SharingHistory grant, InsightResult insight) {
        if (insight == null) {
            // 인사이트는 지우지 않는 테이블이라 올 일이 없다. 와도 목록 전체를 500 내지는 않는다.
            log.warn("sharing grant points at a missing insight insightId={}", grant.getInsightResultId());
            return null;
        }
        List<Long> receivers = sharingHistoryRepository
                .findByInsightResultIdAndReceiverType(insight.getId(), ReceiverType.ORGANIZATION).stream()
                .filter(SharingHistory::isActive)
                .map(SharingHistory::getReceiverId)
                .toList();
        Map<Long, Organization> organizations = organizationRepository
                .findAllById(Stream.concat(receivers.stream(), Stream.of(grant.getSenderOrganizationId())).toList())
                .stream().collect(Collectors.toMap(Organization::getId, org -> org));

        List<InstitutionResponse> sharedWith = receivers.stream().sorted()
                .map(organizations::get)
                .filter(org -> org != null)
                .map(InstitutionResponse::from)
                .toList();
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
                sharedWith,
                grant.getReadAt());
    }

    /**
     * Gate 2 목록과 같은 문구 규칙이다 ({@code InsightGate2QueryService.periodLabel}). 기관 수신함
     * ({@link InstitutionInboxService})도 이것을 쓴다 — 보호자와 기관이 같은 글을 다른 기간 이름으로 부르지 않게.
     */
    static String periodLabel(LocalDate periodFrom) {
        int weekOfMonth = (periodFrom.getDayOfMonth() - 1) / 7 + 1;
        return "%d년 %d월 %d주차".formatted(periodFrom.getYear(), periodFrom.getMonthValue(), weekOfMonth);
    }
}
