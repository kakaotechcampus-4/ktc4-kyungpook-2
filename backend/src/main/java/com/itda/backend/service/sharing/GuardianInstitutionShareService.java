package com.itda.backend.service.sharing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.guardian.InstitutionSharesResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.child.GuardianChildGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보호자의 기관 공유 내역 (G-45) — 내 아이 주간 리포트가 어느 기관에 언제 열렸고 지금도 보이는지.
 *
 * <p>동의 화면과 권한 끄기 확인창이 "동의한 기관과 서로 봐요", "끄면 가려져요" 라고 말한 것을 보호자가 확인하는
 * 곳이다. 그래서 <b>거둔 권한도 보여 준다</b>. "지금 볼 수 있음" 은 기관 수신함과 같은 판정
 * ({@link OrganizationGrantVisibility}) 이라 보호자 화면과 기관 수신함이 어긋나지 않는다.
 *
 * <p><b>가려진 이유는 주지 않는다.</b> 공유 끔 · 연결 해제 · 명부 제외를 나누면 다른 보호자의 연결 해제 같은 일이
 * 드러난다. 기관이 열어 봤는지(열람 시각)도 주지 않는다 — 보여 줄지는 아직 정하지 않았다.
 *
 * <p>보호자는 제3자가 아니라서 어느 기관이 받았는지 보여도 기관 경계 문제가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianInstitutionShareService {

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final InsightResultRepository insightResultRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;

    /**
     * 기관별 묶음. 기관은 이름순, 묶음 안은 열린 순서 최신부터. 아이 한 명 분량이라 글 수와 상관없이 조회 횟수가 같다.
     * 연결되지 않았거나 삭제된 아이면 이유를 나누지 않고 403 이다 — 보호자 홈과 같은 기준이다.
     */
    @Transactional(readOnly = true)
    public List<InstitutionSharesResponse> getShares(String userId, Long childId) {
        Long parentId = userService.getParentIdOf(userId);
        Child child = guardianChildGuard.requireLinkedChild(parentId, childId);

        List<SharingHistory> grants = sharingHistoryRepository.findByChildIdAndReceiverType(
                childId, ReceiverType.ORGANIZATION);
        if (grants.isEmpty()) {
            return List.of();
        }

        Map<Long, InsightResult> insights = insightResultRepository
                .findAllById(grants.stream().map(SharingHistory::getInsightResultId).distinct().toList()).stream()
                .collect(Collectors.toMap(InsightResult::getId, Function.identity()));
        Map<Long, Organization> organizations = organizationRepository
                .findAllById(grants.stream()
                        .flatMap(grant -> Stream.of(grant.getReceiverId(), grant.getSenderOrganizationId()))
                        .distinct()
                        .toList())
                .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));
        Map<Long, ChildOrganization> enrollments = childOrganizationRepository.findByChildIdAndDeletedAtIsNull(childId)
                .stream()
                .collect(Collectors.toMap(ChildOrganization::getOrganizationId, Function.identity(), (a, b) -> a));
        Map<Long, ConsentState> consents = consentStateReader.statesOf(List.of(childId))
                .getOrDefault(childId, Map.of());

        Map<Long, List<InstitutionSharesResponse.SharedInsight>> byReceiver = new LinkedHashMap<>();
        grants.stream()
                .sorted(Comparator.comparing(SharingHistory::getGrantedAt).reversed()
                        .thenComparing(SharingHistory::getId, Comparator.reverseOrder()))
                .forEach(grant -> {
                    InsightResult insight = insights.get(grant.getInsightResultId());
                    if (insight == null) {
                        // 인사이트는 지우지 않는 테이블이라 올 일이 없다. 와도 내역 전체를 500 내지는 않는다.
                        log.warn("sharing grant points at a missing insight insightId={}", grant.getInsightResultId());
                        return;
                    }
                    Long receiverId = grant.getReceiverId();
                    boolean visible = OrganizationGrantVisibility.readable(grant, child, enrollments.get(receiverId),
                            consents.getOrDefault(receiverId, ConsentState.NOT_GRANTED));
                    Organization sender = organizations.get(grant.getSenderOrganizationId());
                    byReceiver.computeIfAbsent(receiverId, k -> new ArrayList<>())
                            .add(new InstitutionSharesResponse.SharedInsight(
                                    String.valueOf(insight.getId()),
                                    GuardianInsightService.periodLabel(insight.getPeriodFrom()),
                                    sender == null ? null : InstitutionResponse.from(sender),
                                    grant.getGrantedAt(),
                                    visible,
                                    visible ? null : grant.getRevokedAt()));
                });

        return byReceiver.entrySet().stream()
                .map(entry -> {
                    Organization receiver = organizations.get(entry.getKey());
                    if (receiver == null) {
                        log.warn("sharing grant points at a missing organization organizationId={}", entry.getKey());
                        return null;
                    }
                    return new InstitutionSharesResponse(InstitutionResponse.from(receiver), entry.getValue());
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(response -> response.institution().name()))
                .toList();
    }
}
