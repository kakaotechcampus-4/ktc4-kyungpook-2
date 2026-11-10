package com.itda.backend.service.sharing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import com.itda.backend.domain.invitation.ChildGuardian;
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
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;
import com.itda.backend.service.child.ActivityLine;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.insight.InsightEvidenceReader;
import com.itda.backend.service.insight.InsightEvidenceReader.Ref;
import com.itda.backend.service.insight.QuietWeekReader;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.child.GuardianChildGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianInsightService {

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;
    private final OrganizationRepository organizationRepository;
    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final SharingReadRecorder sharingReadRecorder;
    private final ApprovalRepository approvalRepository;
    private final InsightEvidenceReader insightEvidenceReader;
    private final ChildGuardianRepository childGuardianRepository;
    private final QuietWeekReader quietWeekReader;

    @Transactional(readOnly = true)
    public List<GuardianInsightResponse> getInsights(String userId) {
        return getInsights(userId, null);
    }

    @Transactional(readOnly = true)
    public List<GuardianInsightResponse> getInsights(String userId, Long childId) {
        Long guardianId = userService.getParentIdOf(userId);
        if (childId != null) {
            guardianChildGuard.requireLinkedChild(guardianId, childId);
        }
        List<SharingHistory> mine = childId == null
                ? sharingHistoryRepository.findByReceiverTypeAndReceiverIdAndRevokedAtIsNullOrderByIdDesc(
                        ReceiverType.GUARDIAN, guardianId)
                : sharingHistoryRepository.findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
                        childId, ReceiverType.GUARDIAN, guardianId);
        List<GuardianInsightResponse> reports = List.of();
        if (!mine.isEmpty()) {
            List<Long> insightIds = mine.stream().map(SharingHistory::getInsightResultId).toList();
            Map<Long, InsightResult> insights = insightResultRepository.findAllById(insightIds)
                    .stream().collect(Collectors.toMap(InsightResult::getId, insight -> insight));
            reports = toResponses(mine, insights, approvedAtOf(insightIds));
        }

        return Stream.concat(reports.stream(), quietWeeksOf(guardianId, childId).stream())
                .sorted(Comparator.comparing(GuardianInsightResponse::approvedAt).reversed()
                        .thenComparing(response -> GuardianInsightResponse.INSIGHT.equals(response.kind())
                                ? Long.parseLong(response.id()) : -1L, Comparator.reverseOrder())
                        .thenComparing(GuardianInsightResponse::id))
                .toList();
    }

    private List<GuardianInsightResponse> quietWeeksOf(Long guardianId, Long onlyChildId) {
        List<Long> childIds = onlyChildId != null ? List.of(onlyChildId)
                : childGuardianRepository.findByUserIdAndDeletedAtIsNullOrderByLinkedAtAscIdAsc(guardianId)
                        .stream().map(ChildGuardian::getChildId).distinct().toList();
        if (childIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = childRepository.findByIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.toMap(Child::getId, Child::getName));
        return quietWeekReader.read(names.keySet()).stream()
                .map(week -> GuardianInsightResponse.quiet(String.valueOf(week.childId()), names.get(week.childId()),
                        periodLabel(week.periodFrom()), week.periodFrom(), week.periodTo(), week.reason().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Set<Long> childIdsWithUnread(Long guardianId, Collection<Long> childIds) {
        if (childIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(sharingHistoryRepository.findChildIdsWithUnread(childIds, ReceiverType.GUARDIAN, guardianId));
    }

    @Transactional
    public GuardianInsightResponse getInsight(String userId, Long insightId) {
        Long guardianId = userService.getParentIdOf(userId);
        SharingHistory grant = sharingReadRecorder.lockActive(insightId, ReceiverType.GUARDIAN, guardianId);
        InsightResult insight = insightResultRepository.findById(insightId).orElse(null);
        if (insight == null) {
            log.warn("sharing grant points at a missing insight insightId={}", insightId);
            throw new SharingException(SharingErrorCode.NOT_FOUND, insightId);
        }
        SharingHistory read = sharingReadRecorder.markRead(grant);
        return toResponses(List.of(read), Map.of(insightId, insight), approvedAtOf(List.of(insightId))).get(0);
    }

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

    private Map<Long, LocalDateTime> approvedAtOf(Collection<Long> insightIds) {
        return approvalRepository
                .findByTargetTypeAndDecisionAndTargetIdIn(
                        ApprovalTargetType.INSIGHT, ApprovalDecision.APPROVED, insightIds)
                .stream()
                .collect(Collectors.toMap(Approval::getTargetId, Approval::getReviewedAt,
                        (a, b) -> a.isAfter(b) ? a : b));
    }

    private List<GuardianInsightResponse> toResponses(List<SharingHistory> grants,
            Map<Long, InsightResult> insightsById, Map<Long, LocalDateTime> approvedAt) {
        List<SharingHistory> known = grants.stream()
                .filter(grant -> {
                    if (insightsById.containsKey(grant.getInsightResultId())) {
                        return true;
                    }
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
                            insight.getPeriodFrom().toString(),
                            insight.getPeriodTo().toString(),
                            insight.getContent(),
                            InstitutionResponse.fromNullable(sender),
                            sharedWith,
                            approvedAt.getOrDefault(insight.getId(), grant.getGrantedAt()),
                            grant.getReadAt(),
                            child == null ? List.of() : evidenceOf(
                                    evidences.getOrDefault(insight.getId(), InsightEvidenceReader.Evidence.EMPTY),
                                    organizations),
                            GuardianInsightResponse.INSIGHT,
                            null);
                })
                .toList();
    }

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
                        InstitutionResponse.from(organizations.get(ref.institutionId())),
                        ref.lookedUp(),
                        ref.sentences().stream()
                                .map(s -> new GuardianInsightResponse.Sentence(s.text(), s.role()))
                                .toList()))
                .toList();
    }

    public static String periodLabel(LocalDate periodFrom) {
        return "%d년 %s".formatted(periodFrom.getYear(), weekLabel(periodFrom));
    }

    static String weekLabel(LocalDate periodFrom) {
        int weekOfMonth = (periodFrom.getDayOfMonth() - 1) / 7 + 1;
        return "%d월 %d주차".formatted(periodFrom.getMonthValue(), weekOfMonth);
    }
}
