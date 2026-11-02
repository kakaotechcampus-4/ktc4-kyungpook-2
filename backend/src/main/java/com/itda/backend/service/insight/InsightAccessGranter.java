package com.itda.backend.service.insight;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 새로 연결된 보호자에게 그 아이의 승인된 인사이트를 연다 (#287). {@link InsightAccessRevoker#revokeForGuardian} 의 짝이다.
 *
 * <p>Gate 2 승인은 <b>그 순간 연결돼 있던 보호자</b>에게만 권한을 만든다. 그것만 두면 엄마가 먼저 가입하고 아빠가
 * 나중에 연결될 때 아빠는 지난 글을 영영 못 본다. 보호자 권한은 연결을 끊을 때만 거두고 재료 기관의 동의 철회로는
 * 거두지 않으므로, <b>보호자가 볼 수 있는 글 = 그 아이의 승인된 인사이트 전부</b>다. 승인 순간을 놓친 보호자에게
 * 연결 순간 열어 그 등식을 맞춘다.
 *
 * <p><b>기관은 다르다.</b> 기관은 동의한 뒤에 승인된 글부터 받는다 — 그게 공유 동의의 뜻이다. 보호자는 아이 기록의
 * 당사자라 그런 제한이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightAccessGranter {

    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * 그 아이의 승인된 인사이트를 이 보호자에게 연다. 검토 전·보류 글은 열지 않는다.
     *
     * <ul>
     * <li>예전 행이 있으면(연결을 끊었다 다시 연결) 되살린다. 새로 넣으면 UNIQUE 에 걸린다. <b>{@code read_at} 은
     * 그대로 둔다</b> — 실제로 본 글을 안 본 것으로 바꾸면 감사 기록이 틀어진다</li>
     * <li>없으면 새로 만든다. 보낸 기관은 같은 글의 다른 권한 행과 같다 — 승인할 때 한 기관으로 정해졌다</li>
     * </ul>
     *
     * <p>부르는 쪽이 {@code child} 행을 FOR UPDATE 로 잠근 상태여야 한다 (§3.3). Gate 2 승인도 같은 행을 잠근 뒤
     * 보호자 목록을 읽으므로, 둘이 겹쳐도 어느 쪽이 먼저든 이 보호자에게 한 번만, 빠짐없이 열린다.
     */
    @Transactional
    public void grantForLinkedGuardian(Long childId, Long guardianId, LocalDateTime at) {
        List<InsightResult> approved =
                insightResultRepository.findByChildIdAndStatusOrderByIdAsc(childId, InsightStatus.APPROVED);
        if (approved.isEmpty()) {
            return;
        }
        List<SharingHistory> grants = sharingHistoryRepository
                .findByInsightResultIdIn(approved.stream().map(InsightResult::getId).toList());
        Map<Long, SharingHistory> mine = new HashMap<>();
        Map<Long, Long> senders = new HashMap<>();
        for (SharingHistory grant : grants) {
            senders.putIfAbsent(grant.getInsightResultId(), grant.getSenderOrganizationId());
            if (grant.getReceiverType() == ReceiverType.GUARDIAN && grant.getReceiverId().equals(guardianId)) {
                mine.put(grant.getInsightResultId(), grant);
            }
        }
        Map<Long, Organization> organizations = organizationsWithoutSender(approved, senders);

        int reopened = 0;
        List<SharingHistory> opened = new ArrayList<>();
        for (InsightResult insight : approved) {
            SharingHistory existing = mine.get(insight.getId());
            if (existing != null) {
                if (!existing.isActive()) {
                    existing.regrant(at);
                    reopened++;
                }
                continue;
            }
            Long senderId = senders.get(insight.getId());
            if (senderId == null) {
                // 승인할 때 보호자도 동의 기관도 없어 행이 하나도 없던 글이다. 승인 주체 규칙으로 다시 구한다.
                Organization primary = PrimarySource.of(PrimarySource.sourceIdsOf(insight), organizations);
                senderId = primary == null ? null : primary.getId();
            }
            if (senderId == null) {
                // 근거 기관이 모두 사라졌다. 보낸 기관이 없는 권한은 "누가 연 글인지" 가 없어 만들지 않는다.
                log.warn("approved insight has no sender, skip guardian grant insightId={} guardianId={}",
                        insight.getId(), guardianId);
                continue;
            }
            opened.add(SharingHistory.grant(childId, insight.getId(), senderId, ReceiverType.GUARDIAN, guardianId));
        }
        sharingHistoryRepository.saveAll(opened);
        if (!opened.isEmpty() || reopened > 0) {
            log.info("insight access granted to linked guardian childId={} guardianId={} opened={} reopened={}",
                    childId, guardianId, opened.size(), reopened);
        }
    }

    /** 보낸 기관을 권한 행에서 못 찾은 글의 근거 기관만 한 번에 읽는다. 대부분은 비어 있어 질의하지 않는다. */
    private Map<Long, Organization> organizationsWithoutSender(List<InsightResult> approved, Map<Long, Long> senders) {
        List<Long> sourceIds = approved.stream()
                .filter(insight -> !senders.containsKey(insight.getId()))
                .flatMap(insight -> PrimarySource.sourceIdsOf(insight).stream())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return organizationRepository.findAllById(sourceIds).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
    }
}
