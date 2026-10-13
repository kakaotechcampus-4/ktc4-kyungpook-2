package com.itda.backend.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.Approval;
import com.itda.backend.domain.ApprovalDecision;
import com.itda.backend.domain.ApprovalTargetType;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    List<Approval> findByTargetTypeAndTargetId(ApprovalTargetType targetType, Long targetId);

    /**
     * 여러 대상의 검토 이력 중 특정 결정만 한 번에 가져온다.
     *
     * <p>인사이트 재료를 고를 때 "이 요약들 중 교사가 고쳐서 승인한 것"을 찾는 데 쓴다. 한 건씩
     * {@link #findByTargetTypeAndTargetId} 로 물으면 요약 수만큼 쿼리가 나간다.
     */
    List<Approval> findByTargetTypeAndDecisionAndTargetIdIn(
            ApprovalTargetType targetType, ApprovalDecision decision, Collection<Long> targetIds);
}
