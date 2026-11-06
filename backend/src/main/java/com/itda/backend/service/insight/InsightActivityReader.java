package com.itda.backend.service.insight;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.repository.insight.InsightResultRepository;

import lombok.RequiredArgsConstructor;

/** 대시보드 "오늘 AI가 한 일"의 인사이트 수 (#370). 근거 기관 판정이 {@link PrimarySource} 에 있어 이 패키지에 둔다. */
@Service
@RequiredArgsConstructor
public class InsightActivityReader {

    private final InsightResultRepository insightResultRepository;

    /**
     * {@code since} 뒤로 만든 인사이트 중 그 기관이 근거를 낸 것의 수. 근거 기관이 JSON 글이라 그날 만든 행을 모두 읽고
     * 자바에서 거른다 — 하루치라 가볍다.
     */
    @Transactional(readOnly = true)
    public long countCreatedSince(Long organizationId, LocalDateTime since) {
        return insightResultRepository.findByCreatedAtGreaterThanEqual(since).stream()
                .filter(insight -> PrimarySource.sourceIdsOf(insight).contains(organizationId))
                .count();
    }
}
