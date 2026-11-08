package com.itda.backend.service.notify;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.request.notify.NotifyAgentRequest;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.insight.InstitutionTypeNames;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보호자에게 보낼 거리를 모은다 (#419).
 *
 * <p><b>승인된 인사이트만 모은다.</b> Gate 2 를 지나지 않은 글은 여기 오지 않는다 — 발송은 판정이 아니다.
 *
 * <p><b>이메일 주소를 다루지 않는다.</b> BE 는 끝까지 주소를 모른다. AI 가 {@code childId} 로 MCP 서버에
 * 물어보고, MCP 서버가 찾는다 (AI/notify/CRITERIA.md §2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final InsightResultRepository insightResultRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;

    /**
     * 그 아이·그 기간에 보낼 거리. 승인된 인사이트가 없으면 {@link Optional#empty()} 다.
     *
     * <p>비어 있으면 <b>집지 않는다</b> — 집어서 SENT 로 남기면, 그 뒤에 교사가 승인해도 그 기간을 다시
     * 보지 않는다. 인사이트 워커가 재료 없는 기간을 집지 않는 것과 같은 이유다.
     */
    @Transactional(readOnly = true)
    public Optional<PreparedNotify> prepare(Long childId, LocalDate periodFrom, LocalDate periodTo) {
        List<InsightResult> approved = insightResultRepository
                .findByChildIdAndStatusOrderByIdAsc(childId, InsightStatus.APPROVED).stream()
                .filter(insight -> !insight.getPeriodFrom().isBefore(periodFrom))
                .filter(insight -> !insight.getPeriodTo().isAfter(periodTo))
                .toList();
        if (approved.isEmpty()) {
            return Optional.empty();
        }

        Optional<Child> child = childRepository.findById(childId);
        if (child.isEmpty()) {
            // 아이가 지워졌다. 보낼 곳이 없다 — 오류가 아니라 보낼 것이 없는 것이다.
            log.info("child not found, nothing to notify childId={}", childId);
            return Optional.empty();
        }

        Map<Long, Organization> institutions = institutionsOf(approved);
        List<NotifyAgentRequest.ApprovedInsight> insights = approved.stream()
                .map(insight -> new NotifyAgentRequest.ApprovedInsight(
                        insight.getContent(),
                        institutionTypesOf(insight, institutions),
                        insight.isCrossInstitution()))
                .toList();

        long lastInsightId = approved.get(approved.size() - 1).getId();
        // notificationId 는 집은 뒤에야 정해진다 (insight_notification.id). 여기서는 비워 두고 워커가 채운다.
        NotifyAgentRequest request = new NotifyAgentRequest(
                childId, child.get().getName(), periodFrom.toString(), periodTo.toString(),
                insights, null, otherChildNames(childId, institutions.keySet()));
        return Optional.of(new PreparedNotify(request, insights.size(), lastInsightId));
    }

    /** 인사이트들의 근거 기관을 한 번에 가져온다. 기관마다 묻지 않는다. */
    private Map<Long, Organization> institutionsOf(List<InsightResult> approved) {
        Set<Long> ids = approved.stream()
                .flatMap(insight -> parseIds(insight.getSourceInstitutionIds()).stream())
                .collect(Collectors.toSet());
        return organizationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Organization::getId, organization -> organization));
    }

    /**
     * 그 인사이트의 근거 기관 종류. <b>한국어다</b> — {@code "학교"}·{@code "센터"}·{@code "활동지원사"}.
     *
     * <p>AI 가 이 값으로 "학교에서는" 을 쓸 수 있는지 가른다. 기관 <b>이름</b>은 보내지 않는다 —
     * 다른 기관 이름이 보호자 메일에 나가면 기관 경계가 새는 것이고, 보호자는 기관마다 따로 동의했을 뿐이다.
     */
    private List<String> institutionTypesOf(InsightResult insight, Map<Long, Organization> institutions) {
        return parseIds(insight.getSourceInstitutionIds()).stream()
                .map(institutions::get)
                .filter(organization -> organization != null)
                .map(organization -> InstitutionTypeNames.of(organization.getType()))
                .distinct()
                .toList();
    }

    /**
     * 메일 본문에 남았는지 대조할 다른 아이 이름 — <b>같은 기관에 다니는 아이들</b>이다.
     *
     * <p>요약은 일지에 이름이 나온 아이들({@code mentioned_child_ids})만 본다. 알림은 그보다 넓게 본다.
     * 메일은 기관 경계 밖으로 나가는 유일한 길이라, 어느 일지에서 왔는지보다 <b>샐 수 있는 이름 전부</b>를
     * 막는 쪽이 맞다.
     *
     * <p>명부에서 빠진 아이도 이름이 새면 안 되므로 {@code deletedAt} 으로 거르지 않는다.
     */
    private List<String> otherChildNames(Long childId, Set<Long> institutionIds) {
        if (institutionIds.isEmpty()) {
            return List.of();
        }
        Set<Long> others = new TreeSet<>();
        institutionIds.forEach(institutionId -> childOrganizationRepository
                .findByOrganizationIdAndDeletedAtIsNull(institutionId)
                .forEach(link -> others.add(link.getChildId())));
        others.remove(childId);
        if (others.isEmpty()) {
            return List.of();
        }
        return childRepository.findAllById(others).stream()
                .sorted(Comparator.comparing(Child::getId))
                .map(Child::getName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            // 저장된 JSON 이 깨졌다. 기관을 모르면 "학교에서는" 을 못 쓰게 되는 쪽으로 기운다 — 안전한 방향이다.
            log.warn("could not read institution ids: {}", json, e);
            return List.of();
        }
    }
}
