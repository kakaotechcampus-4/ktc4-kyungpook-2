package com.itda.backend.service.insight;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.insight.InsightResultRepository;

import lombok.RequiredArgsConstructor;

/** 대시보드 "오늘 AI가 한 일"의 인사이트 수 (#370). 근거 기관 판정이 {@link PrimarySource} 에 있어 이 패키지에 둔다. */
@Service
@RequiredArgsConstructor
public class InsightActivityReader {

    private final InsightResultRepository insightResultRepository;
    private final ChildOrganizationRepository childOrganizationRepository;

    /**
     * {@code since} 뒤로 만든 인사이트 중 지금 우리 명부 아이의, 그 기관이 근거를 낸 것의 수. 근거 기관이 JSON 글이라
     * 우리 명부 아이의 그날 행만 읽고 자바에서 거른다 — 다른 기관 아이의 행은 읽지 않는다.
     */
    @Transactional(readOnly = true)
    public long countCreatedSince(Long organizationId, LocalDateTime since) {
        List<Long> childIds = childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream().map(ChildOrganization::getChildId).distinct().toList();
        if (childIds.isEmpty()) {
            return 0;
        }
        return insightResultRepository.findByChildIdInAndCreatedAtGreaterThanEqual(childIds, since).stream()
                .filter(insight -> PrimarySource.sourceIdsOf(insight).contains(organizationId))
                .count();
    }
}
