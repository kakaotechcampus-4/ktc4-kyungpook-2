package com.itda.backend.service.child;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalTargetType;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.child.OrganizationTimelineEntryResponse;
import com.itda.backend.repository.approval.ApprovalRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기관 아동 상세의 기록 타임라인 (api-spec O-14, #292). 우리 기관이 Gate 1 에서 승인한 요약을 아이 한 명 기준으로 모은다.
 *
 * <p><b>우리 기관 기록만이다.</b> 기관끼리 기록이 넘어가는 길은 사람이 Gate 2 에서 승인한 인사이트(수신함)뿐이다. 다른
 * 기관의 하루 기록을 여기서 보이면 그 경계가 무너진다. 다른 기관이 이 아이를 기록 중이라는 사실도 알리지 않는다.
 *
 * <ul>
 * <li>기록이 없는 날은 줄을 만들지 않는다 — 추정치로 채우지 않는다</li>
 * <li>같은 날 승인본이 둘 이상이면 따로 준다. 승인 뒤 같은 날 일지가 더 오면 새 일지만 묶은 새 요약이 따로 승인된다</li>
 * <li>동의가 꺼져도 지난 기록은 보인다. 동의 철회는 다른 기관으로 넘어가는 것과 새 처리를 막는 장치이고, 원본은 우리
 *     기관이 쓴 기록이다</li>
 * </ul>
 *
 * <p>잠그지 않는다 — 읽기만 하고 열람 기록도 남기지 않는다. 명부 제외와 겹치면 진행 중이던 조회 한 번은 나갈 수 있다
 * (보호자 일지 목록과 같다).
 *
 * <p>ponytail: 그 아이의 승인본을 모두 읽은 뒤 요약의 기관으로 거른다. 승인본 행에는 기관이 없어 요약까지 읽어야 한다.
 * 아이 한 명 분량이라 가볍다. 무거워지면 {@code child_context} ↔ {@code summary_result} 조인 질의로 바꾸고, 건수가
 * 많아지면 나눠 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationTimelineQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OrganizationChildGuard organizationChildGuard;
    private final ChildContextRepository childContextRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final ApprovalRepository approvalRepository;

    /**
     * 관찰 날짜 최근 순, 같은 날은 승인 최근 순. 승인 최근 순(id 내림차순)으로 읽어 날짜로만 다시 줄 세운다 — 정렬이
     * 안정적이라 같은 날 안에서는 승인 순서가 남는다. 조회 수는 건수와 무관하다(승인본 → 요약 → 수정 승인 기록).
     */
    @Transactional(readOnly = true)
    public List<OrganizationTimelineEntryResponse> timeline(String userId, Long childId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        List<ChildContext> contexts = childContextRepository.findByChildIdOrderByIdDesc(childId);
        if (contexts.isEmpty()) {
            return List.of();
        }
        Map<Long, SummaryResult> ours = summaryResultRepository
                .findAllById(contexts.stream().map(ChildContext::getSummaryResultId).distinct().toList())
                .stream()
                .filter(summary -> organizationId.equals(summary.getInstitutionId()))
                .collect(Collectors.toMap(SummaryResult::getId, Function.identity()));
        if (ours.isEmpty()) {
            return List.of();
        }
        Set<Long> edited = approvalRepository
                .findByTargetTypeAndDecisionAndTargetIdIn(
                        ApprovalTargetType.SUMMARY, ApprovalDecision.CORRECTED, ours.keySet())
                .stream()
                .map(Approval::getTargetId)
                .collect(Collectors.toSet());

        // 응답은 승인본 순서로 조립한다. 본문은 승인본의 것 — 요약의 content 는 AI 원문이다
        return contexts.stream()
                .map(context -> {
                    SummaryResult summary = ours.get(context.getSummaryResultId());
                    return summary == null ? null : new OrganizationTimelineEntryResponse(
                            String.valueOf(context.getId()),
                            summary.getEntryDate(),
                            context.getContent(),
                            edited.contains(summary.getId()),
                            sourceCountOf(summary));
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OrganizationTimelineEntryResponse::date).reversed())
                .toList();
    }

    /**
     * 그 요약에 묶인 원본 일지 수. Gate 1 조회({@code SummaryGate1QueryService})와 같은 규칙으로 읽는다 — 비었거나
     * 배열이 아니면 0, 깨졌으면 0 과 경고 로그. 한 건 때문에 목록 전체를 500 내지 않는다.
     */
    private static int sourceCountOf(SummaryResult summary) {
        String raw = summary.getCoveredEntryIds();
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return 0;
            }
            int count = 0;
            for (JsonNode id : node) {
                if (id.canConvertToLong()) {
                    count++;
                }
            }
            return count;
        } catch (JsonProcessingException e) {
            log.warn("failed to parse summary_result.covered_entry_ids id={}", summary.getId(), e);
            return 0;
        }
    }
}
