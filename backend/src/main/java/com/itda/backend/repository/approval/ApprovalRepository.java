package com.itda.backend.repository.approval;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.approval.Approval;
import com.itda.backend.domain.approval.ApprovalDecision;
import com.itda.backend.domain.approval.ApprovalTargetType;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    List<Approval> findByTargetTypeAndTargetId(ApprovalTargetType targetType, Long targetId);

    List<Approval> findByTargetTypeAndDecisionAndTargetIdIn(
            ApprovalTargetType targetType, ApprovalDecision decision, Collection<Long> targetIds);
}
