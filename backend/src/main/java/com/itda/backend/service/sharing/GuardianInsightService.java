package com.itda.backend.service.sharing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.dto.response.child.ParentActivityResponse;
import com.itda.backend.dto.response.guardian.GuardianInsightResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.child.ActivityLine;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightEvidenceReader;
import com.itda.backend.service.insight.InsightEvidenceReader.Ref;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보호자가 받은 인사이트를 읽는다 (#233).
 *
 * <p>Gate 2 승인이 보호자와 동의한 기관에 권한을 함께 연다. 보호자는 여기서 읽기만 한다 — 글마다 기관을
 * 고르는 단계는 10/26 기획 변경으로 없앴다. 특정 기관을 막고 싶으면 그 기관 동의를 끈다.
 *
 * <p><b>"함께 보는 기관"({@code sharedWith}) 은 기관 수신함 · 기관 공유 내역(G-45)과 같은 판정이다</b>
 * ({@link OrganizationGrantVisibility}). 거둔 기록만 보면, 거두기 없이 명부에서 빠진 기관이 리포트 상세에는 남고 공유
 * 내역에는 "가려짐" 으로 보여 같은 사실이 두 화면에서 달라진다 (#319).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianInsightService {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;
    private final OrganizationRepository organizationRepository;
    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final SharingReadRecorder sharingReadRecorder;
    private final ApprovalRepository approvalRepository;
    private final InsightEvidenceReader insightEvidenceReader;

    /**
     * 내게 열린 인사이트 전부, <b>승인 시각</b> 최근 순 (#287). 권한 행 순서가 아니다 — 늦게 연결된 보호자에게는 지난 글이
     * 한꺼번에 열리는데, 그 순서를 따르면 지난 글이 모두 "오늘 온 글" 로 맨 위에 쌓이고 엄마·아빠 화면이 달라진다.
     * 승인 기록이 없는 예전 데이터는 열린 시각으로 대신한다. 같으면 id 큰 순.
     *
     * <p>받은 기관 · 아이 · 명부 · 동의는 모아서 한 번씩 읽는다 ({@link #toResponses}) — 글 수와 상관없이 조회 횟수가 같다.
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
        List<Long> insightIds = mine.stream().map(SharingHistory::getInsightResultId).toList();
        Map<Long, InsightResult> insights = insightResultRepository.findAllById(insightIds)
                .stream().collect(Collectors.toMap(InsightResult::getId, insight -> insight));
        Map<Long, LocalDateTime> approvedAt = approvedAtOf(insightIds);

        return toResponses(mine, insights, approvedAt).stream()
                .sorted(Comparator.comparing(GuardianInsightResponse::approvedAt).reversed()
                        .thenComparing(response -> Long.valueOf(response.id()), Comparator.reverseOrder()))
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
        // 응답은 markRead 가 다시 읽어 돌려준 행으로 만든다 — 넘긴 grant 의 readAt 은 낡은 값이다
        SharingHistory read = sharingReadRecorder.markRead(grant);
        return toResponses(List.of(read), Map.of(insightId, insight), approvedAtOf(List.of(insightId))).get(0);
    }

    /**
     * 보호자 홈 "최근 공유 활동" 의 인사이트 줄 (#289). 이 보호자에게 <b>지금 열린</b> 이 아이의 글만 본다 — 거둬진 글로
     * 줄을 만들면 눌렀을 때 404 다. 같은 기간·같은 승인 기관의 글은 한 줄로 묶는다. 한 번 분석에 여러 편이 나와
     * 담당자가 한 편씩 승인하는데, 같은 문구가 줄줄이 서면 일지 소식이 밀려난다.
     *
     * <p>묶음의 시각은 그중 가장 늦은 승인 시각이다. 승인 기록이 없으면 열린 시각으로 대신한다(목록과 같은 규칙). 보낸
     * 기관 행이 없는 묶음은 뺀다 — 일지 줄과 같이 이름 모르는 기관을 화면에 올리지 않는다. 연결 확인은 부르는 쪽(홈)이
     * 마쳤다.
     */
    @Transactional(readOnly = true)
    public List<ActivityLine> recentActivity(Long guardianId, Long childId, int limit) {
        List<SharingHistory> mine = sharingHistoryRepository.findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
                childId, ReceiverType.GUARDIAN, guardianId);
        if (mine.isEmpty()) {
            return List.of();
        }
        List<Long> insightIds = mine.stream().map(SharingHistory::getInsightResultId).toList();
        Map<Long, InsightResult> insights = insightResultRepository.findAllById(insightIds)
                .stream().collect(Collectors.toMap(InsightResult::getId, insight -> insight));
        Map<Long, LocalDateTime> approvedAt = approvedAtOf(insightIds);
        Map<Long, Organization> senders = organizationRepository
                .findAllById(mine.stream().map(SharingHistory::getSenderOrganizationId).distinct().toList())
                .stream().collect(Collectors.toMap(Organization::getId, org -> org));

        record Opened(InsightResult insight, Organization sender, LocalDateTime at) {
        }
        record Group(LocalDate periodFrom, Long senderId) {
        }
        Map<Group, List<Opened>> groups = mine.stream()
                .map(grant -> {
                    InsightResult insight = insights.get(grant.getInsightResultId());
                    Organization sender = senders.get(grant.getSenderOrganizationId());
                    return insight == null || sender == null ? null : new Opened(insight, sender,
                            approvedAt.getOrDefault(insight.getId(), grant.getGrantedAt()));
                })
                .filter(opened -> opened != null)
                .collect(Collectors.groupingBy(opened -> new Group(opened.insight().getPeriodFrom(),
                        opened.sender().getId())));

        return groups.entrySet().stream()
                .map(entry -> {
                    List<Opened> opened = entry.getValue();
                    LocalDateTime at = opened.stream().map(Opened::at).max(Comparator.naturalOrder()).orElseThrow();
                    String who = opened.get(0).sender().getName();
                    String week = weekLabel(entry.getKey().periodFrom());
                    String text = opened.size() == 1
                            ? "%s에서 %s 리포트가 도착했어요".formatted(who, week)
                            : "%s에서 %s 리포트 %d편이 도착했어요".formatted(who, week, opened.size());
                    String insightId = opened.size() == 1 ? String.valueOf(opened.get(0).insight().getId()) : null;
                    String key = entry.getKey().periodFrom() + "-" + entry.getKey().senderId();
                    return new ActivityLine(at, ParentActivityResponse.insight(key, at, text, insightId));
                })
                .sorted(Comparator.comparing(ActivityLine::at).reversed())
                .limit(limit)
                .toList();
    }

    /** Gate 2 승인 기록의 시각. 인사이트 수와 무관하게 질의 하나다. 한 글에 승인이 둘일 일은 없지만, 있으면 늦은 쪽. */
    private Map<Long, LocalDateTime> approvedAtOf(Collection<Long> insightIds) {
        return approvalRepository
                .findByTargetTypeAndDecisionAndTargetIdIn(
                        ApprovalTargetType.INSIGHT, ApprovalDecision.APPROVED, insightIds)
                .stream()
                .collect(Collectors.toMap(Approval::getTargetId, Approval::getReviewedAt,
                        (a, b) -> a.isAfter(b) ? a : b));
    }

    /**
     * 권한 줄들을 응답으로. 목록(G-35)과 단건(G-36)이 같은 갈래를 써서 어긋나지 않는다.
     *
     * <p>받은 기관 권한 · 아이 · 명부 · 동의 · 기관을 글 묶음 기준으로 한 번씩 읽는다. {@code sharedWith} 는 그 글의 기관
     * 권한 중 <b>받는 기관이 지금 읽을 수 있는 것</b>만 — 기관 수신함과 같은 판정이다 — 기관 이름순이다. 삭제된 아이의 글은
     * {@code childName} 이 {@code null} 이고, 판정에서 모든 기관이 빠져 {@code sharedWith} 가 비어 있다.
     *
     * <p>{@code evidence} 는 Gate 2 와 같은 근거({@link InsightEvidenceReader}) 중 일지 상세(G-21)가 여는 것만이다
     * ({@link #evidenceOf}).
     *
     * @param insightsById 인사이트. 목록은 한 번에 읽은 것을, 단건은 이미 읽은 것을 넘긴다. 없는 글의 줄은 건너뛴다
     * @param approvedAt   글별 Gate 2 승인 시각. 없으면 열린 시각으로 대신한다
     */
    private List<GuardianInsightResponse> toResponses(List<SharingHistory> grants,
            Map<Long, InsightResult> insightsById, Map<Long, LocalDateTime> approvedAt) {
        List<SharingHistory> known = grants.stream()
                .filter(grant -> {
                    if (insightsById.containsKey(grant.getInsightResultId())) {
                        return true;
                    }
                    // 인사이트는 지우지 않는 테이블이라 올 일이 없다. 와도 목록 전체를 500 내지는 않는다.
                    log.warn("sharing grant points at a missing insight insightId={}", grant.getInsightResultId());
                    return false;
                })
                .toList();
        if (known.isEmpty()) {
            return List.of();
        }

        List<Long> insightIds = known.stream().map(SharingHistory::getInsightResultId).distinct().toList();
        Map<Long, List<SharingHistory>> receivedBy = sharingHistoryRepository
                .findByInsightResultIdInAndReceiverType(insightIds, ReceiverType.ORGANIZATION).stream()
                .collect(Collectors.groupingBy(SharingHistory::getInsightResultId));
        List<Long> childIds = insightIds.stream().map(id -> insightsById.get(id).getChildId()).distinct().toList();
        Map<Long, Child> children = childRepository.findByIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<Long, Map<Long, ChildOrganization>> enrollments = childOrganizationRepository
                .findByChildIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.groupingBy(ChildOrganization::getChildId,
                        Collectors.toMap(ChildOrganization::getOrganizationId, Function.identity(), (a, b) -> a)));
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(childIds);
        Map<Long, InsightEvidenceReader.Evidence> evidences = insightEvidenceReader.read(
                insightIds.stream().map(insightsById::get).toList());
        Map<Long, Organization> organizations = organizationRepository
                .findAllById(Stream.of(
                                receivedBy.values().stream().flatMap(List::stream).map(SharingHistory::getReceiverId),
                                known.stream().map(SharingHistory::getSenderOrganizationId),
                                evidences.values().stream().flatMap(evidence -> evidence.institutionIds().stream()))
                        .flatMap(Function.identity())
                        .distinct()
                        .toList())
                .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));

        return known.stream()
                .map(grant -> {
                    InsightResult insight = insightsById.get(grant.getInsightResultId());
                    Long childId = insight.getChildId();
                    Child child = children.get(childId);
                    List<InstitutionResponse> sharedWith = receivedBy.getOrDefault(insight.getId(), List.of()).stream()
                            .filter(received -> OrganizationGrantVisibility.readable(received, child,
                                    enrollments.getOrDefault(childId, Map.of()).get(received.getReceiverId()),
                                    consents.getOrDefault(childId, Map.of())
                                            .getOrDefault(received.getReceiverId(), ConsentState.NOT_GRANTED)))
                            .map(received -> organizations.get(received.getReceiverId()))
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(Organization::getName).thenComparing(Organization::getId))
                            .map(InstitutionResponse::from)
                            .toList();
                    Organization sender = organizations.get(grant.getSenderOrganizationId());
                    return new GuardianInsightResponse(
                            String.valueOf(insight.getId()),
                            String.valueOf(childId),
                            child == null ? null : child.getName(),
                            periodLabel(insight.getPeriodFrom()),
                            // 보호자가 보는 글은 보호자용 문장이다 (#433). 없으면 content 로 떨어진다 —
                            // 옛 행과 AI 검사에 걸린 행이 빈 글이 되면 안 된다.
                            insight.guardianContent(),
                            sender == null ? null : InstitutionResponse.from(sender),
                            sharedWith,
                            approvedAt.getOrDefault(insight.getId(), grant.getGrantedAt()),
                            grant.getReadAt(),
                            child == null ? List.of() : evidenceOf(
                                    evidences.getOrDefault(insight.getId(), InsightEvidenceReader.Evidence.EMPTY),
                                    organizations));
                })
                .toList();
    }

    /**
     * 근거 줄 중 <b>보호자가 지금 일지 상세(G-21)로 열 수 있는 것</b>만, 관찰 날짜 오래된 순 · 같은 날은 기관 이름순 (#405).
     * 승인본 · 요약 · 같은 아이는 {@link InsightEvidenceReader} 가 이미 봤다. 여기서는 일지 상세처럼 기관 행이 없는 줄을 뺀다.
     * 아이 연결은 이 글의 권한이 살아 있다는 것으로 확인됐고, 삭제된 아이는 부르는 쪽이 빈 배열로 둔다.
     *
     * <p><b>동의로 거르지 않는다.</b> 동의를 끈 기관 · 명부에서 빠진 기관의 일지도 보호자는 일지 탭에서 계속 본다
     * ({@code GuardianJournalQueryService}). 여기서만 빼면 글은 그 기관 기록으로 쓴 그대로인데 출처만 사라진다.
     */
    private static List<GuardianInsightResponse.Evidence> evidenceOf(InsightEvidenceReader.Evidence evidence,
            Map<Long, Organization> organizations) {
        return evidence.refs().stream()
                .filter(ref -> organizations.containsKey(ref.institutionId()))
                .sorted(Comparator.comparing(Ref::date)
                        .thenComparing(ref -> organizations.get(ref.institutionId()).getName())
                        .thenComparing(Ref::childContextId))
                .map(ref -> new GuardianInsightResponse.Evidence(
                        String.valueOf(ref.childContextId()),
                        ref.date().toString(),
                        InstitutionResponse.from(organizations.get(ref.institutionId()))))
                .toList();
    }

    /**
     * Gate 2 목록과 같은 문구 규칙이다 ({@code InsightGate2QueryService.periodLabel}). 기관 수신함
     * ({@link InstitutionInboxService})도 이것을 쓴다 — 보호자와 기관이 같은 글을 다른 기간 이름으로 부르지 않게.
     */
    static String periodLabel(LocalDate periodFrom) {
        return "%d년 %s".formatted(periodFrom.getYear(), weekLabel(periodFrom));
    }

    /** 연도 없는 "8월 3주차". 홈 활동 줄처럼 짧게 쓸 때. 주 계산은 {@link #periodLabel} 과 같은 한 곳이다. */
    static String weekLabel(LocalDate periodFrom) {
        int weekOfMonth = (periodFrom.getDayOfMonth() - 1) / 7 + 1;
        return "%d월 %d주차".formatted(periodFrom.getMonthValue(), weekOfMonth);
    }
}
