package com.itda.backend.service.sharing;

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
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.dto.response.sharing.InboxDetailResponse;
import com.itda.backend.dto.response.sharing.InboxItemResponse;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기관 수신함 — 다른 기관이 Gate 2 에서 승인해 우리 기관에 열린 인사이트를 읽는다 (#273).
 *
 * <p><b>읽을 때마다 지금 읽어도 되는지 다시 본다.</b> 열람 방식을 택한 이유가 "철회하면 그 뒤로 안 보인다" 였다.
 * 지금은 권한이 꺼지는 경로(동의 철회, 보호자 연결 해제)가 모두 권한 줄을 거두지만, 나중에 명부에서 내리는 길이
 * 생기면서 거두기를 빠뜨려도 새지 않게 명부·동의·아이를 함께 본다 ({@link #readable}). 목록과 단건이 같은 판정을
 * 쓴다 — 갈리면 목록에서는 숨는데 단건으로는 열리는 구멍이 생긴다.
 *
 * <p>판정에 걸려도 권한 줄을 거두지 않는다. 조회는 권한을 바꾸지 않고 보이지만 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstitutionInboxService {

    private final UserService userService;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final SharingReadRecorder sharingReadRecorder;
    private final InsightResultRepository insightResultRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;

    /**
     * 우리 기관에 열린 글, 최근 열린 순. 본문은 주지 않는다 — 단건을 열어야 보이고, 그때 열람 시각이 남는다.
     * 같은 기간의 옛 회차도 숨기지 않는다. 둘 다 Gate 2 를 지난 글이다. 글 수와 상관없이 조회 횟수가 같다.
     */
    @Transactional(readOnly = true)
    public List<InboxItemResponse> getInbox(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<SharingHistory> received = sharingHistoryRepository
                .findByReceiverTypeAndReceiverIdAndRevokedAtIsNullOrderByIdDesc(
                        ReceiverType.ORGANIZATION, organizationId);
        if (received.isEmpty()) {
            return List.of();
        }

        Map<Long, ChildOrganization> enrollments = childOrganizationRepository
                .findByOrganizationIdAndDeletedAtIsNull(organizationId).stream()
                .collect(Collectors.toMap(ChildOrganization::getChildId, Function.identity(), (a, b) -> a));
        Set<Long> childIds = received.stream().map(SharingHistory::getChildId).collect(Collectors.toSet());
        Map<Long, Child> children = childRepository.findByIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(childIds);

        List<SharingHistory> readable = received.stream()
                .filter(grant -> readable(grant, children.get(grant.getChildId()),
                        enrollments.get(grant.getChildId()),
                        consents.getOrDefault(grant.getChildId(), Map.of())
                                .getOrDefault(organizationId, ConsentState.NOT_GRANTED)))
                .toList();
        if (readable.isEmpty()) {
            return List.of();
        }

        Map<Long, InsightResult> insights = insightResultRepository
                .findAllById(readable.stream().map(SharingHistory::getInsightResultId).toList()).stream()
                .collect(Collectors.toMap(InsightResult::getId, Function.identity()));
        Map<Long, Organization> senders = organizationRepository
                .findAllById(readable.stream().map(SharingHistory::getSenderOrganizationId).distinct().toList())
                .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));

        return readable.stream()
                .map(grant -> {
                    InsightResult insight = insights.get(grant.getInsightResultId());
                    if (insight == null) {
                        // 인사이트는 지우지 않는 테이블이라 올 일이 없다. 와도 목록 전체를 500 내지는 않는다.
                        log.warn("sharing grant points at a missing insight insightId={}",
                                grant.getInsightResultId());
                        return null;
                    }
                    return toItem(grant, insight, children.get(grant.getChildId()),
                            enrollments.get(grant.getChildId()), senders.get(grant.getSenderOrganizationId()));
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 단건. 본문을 주고, 처음 열어 보면 그 시각을 우리 기관 기준으로 남긴다. 판정을 통과한 뒤에만 남긴다 — 못 보는
     * 글을 봤다고 기록하지 않는다. 없는 글·남의 글·막힌 글 모두 같은 404 다. 이유를 나누면 그 아이와 다른 기관
     * 사이에 무슨 일이 있었는지가 샌다.
     */
    @Transactional
    public InboxDetailResponse getInboxItem(String userId, Long insightId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        SharingHistory grant = sharingReadRecorder.lockActive(insightId, ReceiverType.ORGANIZATION, organizationId);
        Long childId = grant.getChildId();

        Child child = childRepository.findById(childId).orElse(null);
        ChildOrganization enrollment = childOrganizationRepository
                .findByChildIdAndOrganizationId(childId, organizationId).orElse(null);
        ConsentState consent = consentStateReader.stateOf(childId, organizationId);
        if (!readable(grant, child, enrollment, consent)) {
            throw notFound(insightId);
        }
        InsightResult insight = insightResultRepository.findById(insightId).orElse(null);
        if (insight == null) {
            log.warn("sharing grant points at a missing insight insightId={}", insightId);
            throw notFound(insightId);
        }

        SharingHistory read = sharingReadRecorder.markRead(grant);
        Organization sender = organizationRepository.findById(read.getSenderOrganizationId()).orElse(null);
        InboxItemResponse item = toItem(read, insight, child, enrollment, sender);
        return new InboxDetailResponse(item.id(), item.childId(), item.childName(), item.externalId(),
                item.from(), item.period(), item.receivedAt(), item.readAt(), insight.getContent());
    }

    /**
     * 우리 기관이 지금 이 글을 읽어도 되는지. 넷 다 참이어야 한다.
     *
     * <ol>
     * <li>권한 줄이 거둬지지 않았다</li>
     * <li>우리 기관 명부에 그 아이가 지금 있다 — 권한은 만들 때 굳지만 명부는 바뀐다</li>
     * <li>우리 기관 기준 동의가 켜져 있다 — 다른 기관 동의로 켜진 아이 전체 상태가 아니다</li>
     * <li>아이가 삭제되지 않았다</li>
     * </ol>
     */
    private static boolean readable(SharingHistory grant, Child child, ChildOrganization enrollment,
            ConsentState consent) {
        return grant.isActive()
                && child != null && !child.isDeleted()
                && enrollment != null && !enrollment.isDeleted()
                && consent == ConsentState.GRANTED;
    }

    private static InboxItemResponse toItem(SharingHistory grant, InsightResult insight, Child child,
            ChildOrganization enrollment, Organization sender) {
        return new InboxItemResponse(
                String.valueOf(insight.getId()),
                String.valueOf(child.getId()),
                child.getName(),
                enrollment.getExternalId(),
                sender == null ? null : InstitutionResponse.from(sender),
                GuardianInsightService.periodLabel(insight.getPeriodFrom()),
                grant.getGrantedAt(),
                grant.getReadAt());
    }

    private static SharingException notFound(Long insightId) {
        return new SharingException(SharingErrorCode.NOT_FOUND, insightId);
    }
}
